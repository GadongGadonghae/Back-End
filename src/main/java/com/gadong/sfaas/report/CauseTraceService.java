package com.gadong.sfaas.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.report.CauseTrace.Cause;
import com.gadong.sfaas.report.CauseTrace.Evidence;
import com.gadong.sfaas.report.CauseTrace.ScorePart;
import com.gadong.sfaas.report.CauseTrace.Sentence;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 고장 원인 추적 (설명 가능한 AI 보고서).
 * <ol>
 *   <li>collect: 고장 건 하나에 대해 DB에서 근거를 모아 번호(E1…)를 붙이고, 원인 후보의 근거 강도를 규칙 점수로 매겨요</li>
 *   <li>write: LLM이 근거 번호를 단 문장을 써요. 키가 없거나 실패하면 근거로 만든 템플릿 문장</li>
 *   <li>verify: 문장마다 근거 번호가 실제로 있는지, 문장 속 숫자가 인용한 근거에 있는지 코드가 확인해요</li>
 * </ol>
 * 근거 강도 = 직접 신호 40 + 시간 근접 20 + 과거 사례 30 + 데이터 완전성 10 (100점). 확률이 아니라 근거가 얼마나 받쳐 주는지예요.
 * 수집 테이블 SQL은 H2(테스트)에서도 돌도록 표준 SQL만 써요.
 */
@Service
public class CauseTraceService {
    private static final Logger log = LoggerFactory.getLogger(CauseTraceService.class);

    static final int HIGH = 70;
    static final int MEDIUM = 40;
    /** 직전 공정값·상태 변화를 보는 구간 */
    private static final Duration LOOKBACK = Duration.ofMinutes(60);
    /** "직전"으로 보는 구간 (평균·이탈) */
    private static final Duration RECENT = Duration.ofMinutes(10);
    /** 고장 전 알람을 찾는 구간 */
    private static final Duration ALARM_WINDOW = Duration.ofMinutes(30);
    private static final int HISTORY_DAYS = 30;
    private static final int MAX_SENTENCES = 8;
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter MDHM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 공정별 수집 테이블과 측정 컬럼 (schema.sql). 프레스는 단(seq_no)마다 자기 칸 하나 */
    private static final Map<String, String> TABLE = Map.of(
            "SLITTING", "slitting_data", "PRESS", "press_data", "WELDING", "welding_data", "PAINTING", "painting_data");
    private static final Map<String, List<String>> COLUMNS = Map.of(
            "SLITTING", List.of("torque", "current", "voltage"),
            "PRESS", List.of("pressure_left", "pressure_right", "pressure_diff", "press_time"),
            "WELDING", List.of("press_force", "current", "voltage", "weld_time"),
            "PAINTING", List.of("ph", "temperature", "current", "voltage"));

    private static final String SYSTEM_PROMPT = """
            당신은 제조 설비 고장의 원인 추적 보고서를 쓰는 전문가다. 다음 규칙을 반드시 지킨다.
            1. evidence와 causes에 있는 사실만 쓴다. 없는 값·부품·원인을 지어내지 않는다.
            2. 문장마다 근거 번호(evidence의 id, causes의 id)를 evidence 배열에 넣는다. 문장 본문에는 번호를 쓰지 않는다.
            3. 숫자는 인용한 근거에 적힌 숫자만 그대로 쓴다. 새로 계산하거나 반올림한 숫자를 만들지 않는다.
            4. kind는 fact(근거에 있는 사실), inference(원인 추정, 근거 강도 등급을 같이 말한다), action(현장 점검 권장) 중 하나다.
            5. 원인은 causes 후보 안에서만 말한다. 근거 강도는 확률이 아니라 규칙 점수다. 등급이 LOW면 단정하지 말고 현장 확인이 필요하다고 쓴다.
            6. 설비를 자동으로 제어하라는 명령은 쓰지 않는다. 한국어 존댓말로, 문장은 4~7개.
            반드시 다음 JSON만 반환한다.
            {"sentences":[{"text":"...","kind":"fact","evidence":["E1"]}]}
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AiReportService ai;
    private final ObjectMapper objectMapper;

    public CauseTraceService(NamedParameterJdbcTemplate jdbc, AiReportService ai, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.ai = ai;
        this.objectMapper = objectMapper;
    }

    // ── 모은 결과 ──

    /** 고장 건 */
    record Incident(long downtimeId, String equipmentId, String equipmentName, String processId, Integer seqNo,
                    String code, String codeName, String detail, LocalDateTime start, LocalDateTime end) {}

    /** 근거·원인 후보와, 검증에 쓸 근거별 원문 (번호 → 글) */
    public record Collected(Incident incident, LocalDateTime from, LocalDateTime to, List<Evidence> evidence, List<Cause> causes,
                           Map<String, String> sourceText, Hints hints) {}

    /** 템플릿 문장에 쓰는 값 */
    record Hints(String topItemLabel, String topItemRange, String trendId, String historyId, String lastMemo,
                 List<String> alarmIds, boolean hasProcessData) {}

    /** 관리 항목 기준 */
    private record Limit(String label, String unit, Double lower, Double upper) {}

    /** 직전 1시간 한 항목의 통계 */
    private record ItemStat(String column, Limit limit, int n, int n10, Double avg, Double recentAvg, Double baseAvg,
                            Integer recentPct, int out10, LocalDateTime lastOut) {}

    // ── 1. 근거 모으기 ──

    public Collected collect(long downtimeId) {
        Incident inc = incident(downtimeId);
        LocalDateTime to = inc.start();
        LocalDateTime from = to.minus(LOOKBACK);
        List<Evidence> ev = new ArrayList<>();
        Map<String, String> text = new LinkedHashMap<>();

        // E1 고장 건
        String dur = inc.end() == null ? "진행 중" : "정지 " + formatDuration(Duration.between(inc.start(), inc.end())) + " (종료 " + HMS.format(inc.end()) + ")";
        add(ev, text, "FAULT", inc.equipmentName() + " " + inc.codeName(),
                HMS.format(inc.start()) + " 시작 · " + dur + (blank(inc.detail()) ? "" : " · 사유 상세: " + inc.detail()),
                inc.start(), "downtime_event#" + inc.downtimeId());

        Map<String, Limit> limits = limits(inc.equipmentId());

        // 직전 알람 (고장 전 30분 ~ 고장 시각)
        List<Map<String, Object>> alarms = jdbc.queryForList("SELECT alarm_id, item_name, measured_value, lower_limit, upper_limit, level, message, raised_at"
                        + " FROM alarm WHERE equipment_id = :eq AND raised_at >= :a AND raised_at <= :b ORDER BY raised_at DESC LIMIT 5",
                Map.of("eq", inc.equipmentId(), "a", to.minus(ALARM_WINDOW), "b", to));
        Map<String, List<String>> alarmIdsByItem = new LinkedHashMap<>();
        Map<String, LocalDateTime> lastAlarmByItem = new HashMap<>();
        Map<String, String> alarmDirection = new HashMap<>();
        List<String> alarmIds = new ArrayList<>();
        for (Map<String, Object> a : alarms) {
            String item = (String) a.get("item_name");
            Limit l = item == null ? null : limits.get(item);
            LocalDateTime at = Kst.local(a.get("raised_at"));
            Double v = num(a.get("measured_value")), lo = num(a.get("lower_limit")), hi = num(a.get("upper_limit"));
            String unit = l == null || l.unit() == null ? "" : l.unit();
            String label = l != null ? l.label() : item == null ? "고장 신호" : item;
            StringBuilder d = new StringBuilder(String.valueOf(a.get("message")));
            if (v != null) d.append(" · 측정 ").append(fmt(v)).append(unit);
            if (lo != null || hi != null) d.append(" · 기준 ").append(range(lo, hi, unit));
            long mb = minutesBefore(at, to);
            d.append(" · 고장 ").append(mb == 0 ? "1분 안" : mb + "분 전").append(" (").append(HMS.format(at)).append(")");
            String id = add(ev, text, "ALARM", label + " 알람 (" + ("WARNING".equals(a.get("level")) ? "경고" : "위험") + ")", d.toString(), at,
                    "alarm#" + a.get("alarm_id"));
            alarmIds.add(id);
            if (item != null) {
                alarmIdsByItem.computeIfAbsent(item, k -> new ArrayList<>()).add(id);
                lastAlarmByItem.merge(item, at, (x, y) -> x.isAfter(y) ? x : y);
                if (v != null && hi != null && v > hi) alarmDirection.putIfAbsent(item, "상한 초과");
                else if (v != null && lo != null && v < lo) alarmDirection.putIfAbsent(item, "하한 미달");
            }
        }

        // 직전 공정값 추이 · 공정 불량
        List<ItemStat> stats = new ArrayList<>();
        Map<String, String> trendIdByItem = new HashMap<>();
        int[] quality = processStats(inc, from, to, limits, stats);
        boolean hasProcessData = stats.stream().anyMatch(s -> s.n() > 0);
        for (ItemStat s : stats) {
            if (s.n() == 0) continue;
            String unit = s.limit() == null || s.limit().unit() == null ? "" : s.limit().unit();
            String label = s.limit() == null ? s.column() : s.limit().label();
            StringBuilder d = new StringBuilder();
            if (s.n10() > 0) {
                d.append("직전 10분 평균 ").append(fmt(s.recentAvg())).append(unit);
                if (s.baseAvg() != null) d.append(" · 그 전 50분 평균 ").append(fmt(s.baseAvg())).append(unit);
            } else {
                d.append("직전 10분 값 없음 · 1시간 평균 ").append(fmt(s.avg())).append(unit);
            }
            if (s.limit() != null && (s.limit().lower() != null || s.limit().upper() != null)) {
                d.append(" · 기준 ").append(range(s.limit().lower(), s.limit().upper(), unit));
            }
            if (s.recentPct() != null) d.append(" · 직전 10분 위험도 ").append(s.recentPct()).append("%");
            if (s.n10() > 0) d.append(" · 직전 10분 측정 ").append(s.n10()).append("점 중 기준 이탈 ").append(s.out10()).append("점");
            trendIdByItem.put(s.column(), add(ev, text, "TREND", label + " 고장 전 1시간", d.toString(), to,
                    tableOf(inc) + " (" + HMS.format(from) + "~" + HMS.format(to) + ")"));
        }
        if (quality != null && quality[1] > 0) {
            add(ev, text, "QUALITY", "고장 전 공정 판정", "직전 10분 공정 판정 " + quality[1] + "개 중 불량 " + quality[0] + "개", to,
                    tableOf(inc) + ".result");
        }
        if (!hasProcessData && TABLE.containsKey(inc.processId())) {
            add(ev, text, "TREND", "고장 전 1시간 공정값", "수집된 공정값 없음 (재생 전이거나 통신 끊김)", to, tableOf(inc));
        }

        // 직전 상태 변화 (통신 끊김 등)
        Map<String, Integer> states = new LinkedHashMap<>();
        LocalDateTime[] lastDc = {null};
        // 설비의 첫 기록인 통신 끊김(DC)은 수집 시작 전 상태라 빼요 (재생·서버 시작 직후)
        jdbc.query("SELECT h.status, h.started_at, h.ended_at FROM equipment_status_history h WHERE h.equipment_id = :eq"
                        + " AND h.started_at >= :a AND h.started_at < :b AND (h.status <> 'DC' OR EXISTS (SELECT 1 FROM equipment_status_history p"
                        + " WHERE p.equipment_id = h.equipment_id AND p.started_at < h.started_at))",
                Map.of("eq", inc.equipmentId(), "a", from, "b", to), rs -> {
                    String st = rs.getString(1);
                    states.merge(st, 1, Integer::sum);
                    if ("DC".equals(st)) {
                        LocalDateTime e = rs.getObject(3) == null ? to : Kst.local(rs.getObject(3));
                        if (lastDc[0] == null || e.isAfter(lastDc[0])) lastDc[0] = e;
                    }
                });
        int dc = states.getOrDefault("DC", 0);
        String stateId = null;
        if (dc + states.getOrDefault("S", 0) + states.getOrDefault("I", 0) + states.getOrDefault("D", 0) > 0) {
            stateId = add(ev, text, "STATE", "고장 전 1시간 상태 변화",
                    "통신 끊김 " + dc + "회 · 정지 " + states.getOrDefault("S", 0) + "회 · 대기 " + states.getOrDefault("I", 0)
                            + "회 · 고장 " + states.getOrDefault("D", 0) + "회", to, "equipment_status_history");
        }

        // 과거 고장 (같은 설비, 최근 30일)
        List<Map<String, Object>> past = jdbc.queryForList("SELECT downtime_id, started_at, ended_at, action_memo FROM downtime_event"
                        + " WHERE equipment_id = :eq AND status = 'D' AND downtime_id <> :id AND started_at >= :a AND started_at < :b"
                        + " ORDER BY started_at DESC LIMIT 20",
                Map.of("eq", inc.equipmentId(), "id", inc.downtimeId(), "a", to.minusDays(HISTORY_DAYS), "b", to));
        Map<String, Integer> pastItemHits = new HashMap<>();
        int pastWithAlarm = 0;
        long minutesSum = 0;
        int ended = 0;
        String lastMemo = null;
        for (Map<String, Object> p : past) {
            LocalDateTime s = Kst.local(p.get("started_at"));
            if (p.get("ended_at") != null) {
                minutesSum += Duration.between(s, Kst.local(p.get("ended_at"))).toMinutes();
                ended++;
            }
            if (lastMemo == null && !blank((String) p.get("action_memo"))) lastMemo = ((String) p.get("action_memo")).trim();
            List<String> items = jdbc.queryForList("SELECT DISTINCT item_name FROM alarm WHERE equipment_id = :eq AND raised_at >= :a"
                    + " AND raised_at <= :b AND item_name IS NOT NULL", Map.of("eq", inc.equipmentId(), "a", s.minus(ALARM_WINDOW), "b", s), String.class);
            if (!items.isEmpty()) pastWithAlarm++;
            items.forEach(i -> pastItemHits.merge(i, 1, Integer::sum));
        }
        int pastCount = past.size();
        String historyDetail = pastCount == 0 ? "최근 30일 같은 설비 고장 없음 (이번이 처음)"
                : "최근 30일 같은 설비 고장 " + pastCount + "건"
                + (ended > 0 ? " · 평균 복구 " + Math.round((double) minutesSum / ended) + "분" : "")
                + " · 마지막 " + MDHM.format(Kst.local(past.get(0).get("started_at")))
                + " · 고장 전 알람이 있던 건 " + pastWithAlarm + "건"
                + (lastMemo == null ? "" : " · 최근 조치 메모: “" + lastMemo + "”");
        String historyId = add(ev, text, "HISTORY", "같은 설비 과거 고장", historyDetail, to.minusDays(HISTORY_DAYS),
                "downtime_event (equipment_id = " + inc.equipmentId() + ")");

        // ── 2. 원인 후보와 근거 강도 ──
        List<Cause> causes = new ArrayList<>();
        Set<String> items = new LinkedHashSet<>(alarmIdsByItem.keySet());
        for (ItemStat s : stats) {
            if (s.out10() > 0 || (s.recentPct() != null && s.recentPct() >= 80)) items.add(s.column());
        }
        for (String item : items) {
            ItemStat s = stats.stream().filter(x -> x.column().equals(item)).findFirst().orElse(null);
            Limit l = limits.get(item);
            String label = l != null ? l.label() : item;
            List<ScorePart> parts = new ArrayList<>();
            int alarmsN = alarmIdsByItem.getOrDefault(item, List.of()).size();
            // 직접 신호
            int direct;
            String directNote;
            if (alarmsN > 0) { direct = 40; directNote = "고장 전 30분 안에 알람 " + alarmsN + "건"; }
            else if (s != null && s.out10() > 0) { direct = 30; directNote = "직전 10분 기준 이탈 " + s.out10() + "점"; }
            else if (s != null && s.recentPct() != null && s.recentPct() >= 90) { direct = 20; directNote = "직전 10분 위험도 " + s.recentPct() + "%"; }
            else { direct = 10; directNote = "직전 10분 위험도 " + (s == null ? "-" : s.recentPct()) + "%"; }
            parts.add(new ScorePart("직접 신호", direct, 40, directNote));
            // 시간 근접
            LocalDateTime signal = lastAlarmByItem.get(item);
            if (signal == null && s != null) signal = s.lastOut();
            parts.add(proximity(signal, to));
            // 과거 사례
            int hits = pastItemHits.getOrDefault(item, 0);
            parts.add(pastCount == 0 ? new ScorePart("과거 사례", 0, 30, "최근 30일 같은 설비 고장 없음")
                    : new ScorePart("과거 사례", Math.round(30f * hits / pastCount), 30, "과거 고장 " + pastCount + "건 중 " + hits + "건에서 같은 항목 알람"));
            // 데이터 완전성
            parts.add(completeness(s == null ? 0 : s.n10(), s == null ? 0 : s.n(), dc));
            String dir = alarmDirection.get(item);
            if (dir == null && s != null && s.recentAvg() != null && l != null && l.upper() != null) {
                if (s.out10() > 0) dir = l.lower() != null && s.recentAvg() < l.lower() ? "하한 미달" : "상한 초과";
                else dir = l.lower() == null || s.recentAvg() >= (l.lower() + l.upper()) / 2 ? "상한 쪽으로 치우침" : "하한 쪽으로 치우침";
            }
            List<String> cites = new ArrayList<>(alarmIdsByItem.getOrDefault(item, List.of()));
            if (trendIdByItem.containsKey(item)) cites.add(trendIdByItem.get(item));
            cites.add(historyId);
            causes.add(cause(label + " 이상" + (dir == null ? "" : " (" + dir + ")"), camel(item), parts, cites));
        }
        if (dc > 0) {
            List<ScorePart> parts = new ArrayList<>();
            parts.add(new ScorePart("직접 신호", 20, 40, "고장 전 1시간 통신 끊김 " + dc + "회"));
            parts.add(proximity(lastDc[0], to));
            parts.add(new ScorePart("과거 사례", 0, 30, "과거 통신 끊김 사례는 집계하지 않음"));
            parts.add(new ScorePart("데이터 완전성", 10, 10, "상태 이력 있음"));
            causes.add(cause("통신 끊김 뒤 고장 (배선·통신 계통)", null, parts, List.of(stateId, historyId)));
        }
        {
            // 공정값 이상 없이 고장 신호만 온 경우: 설비 자체(기계·전기) 고장
            List<ScorePart> parts = new ArrayList<>();
            int n10 = stats.stream().mapToInt(ItemStat::n10).max().orElse(0);
            if (!hasProcessData) parts.add(new ScorePart("직접 신호", 0, 40, "공정값이 없어 판단 근거 부족"));
            else if (items.isEmpty()) parts.add(new ScorePart("직접 신호", 15, 40, "관리값 알람·이탈 없이 고장 신호만 있음"));
            else parts.add(new ScorePart("직접 신호", 0, 40, "관리값 이상이 있어 후순위"));
            parts.add(new ScorePart("시간 근접", 0, 20, "시점을 가리키는 신호 없음"));
            parts.add(pastCount == 0 ? new ScorePart("과거 사례", 0, 30, "최근 30일 같은 설비 고장 없음")
                    : new ScorePart("과거 사례", Math.round(30f * (pastCount - pastWithAlarm) / pastCount), 30,
                    "과거 고장 " + pastCount + "건 중 " + (pastCount - pastWithAlarm) + "건이 알람 없이 발생"));
            parts.add(n10 >= 3 && dc == 0 ? new ScorePart("데이터 완전성", 10, 10, "직전 10분 공정값 " + n10 + "점, 통신 끊김 없음")
                    : n10 > 0 ? new ScorePart("데이터 완전성", 5, 10, "직전 10분 공정값 " + n10 + "점")
                    : new ScorePart("데이터 완전성", 0, 10, "직전 10분 공정값 없음"));
            List<String> cites = new ArrayList<>(List.of(ev.get(0).id()));
            stats.stream().filter(x -> trendIdByItem.containsKey(x.column())).forEach(x -> cites.add(trendIdByItem.get(x.column())));
            cites.add(historyId);
            causes.add(cause("설비 자체 고장 (기계·전기 계통)", null, parts, cites));
        }
        causes.sort(Comparator.comparingInt(Cause::score).reversed());
        List<Cause> top = new ArrayList<>();
        for (int i = 0; i < Math.min(3, causes.size()); i++) {
            Cause c = causes.get(i);
            Cause numbered = new Cause("C" + (i + 1), c.label(), c.item(), c.score(), c.grade(), c.parts(), c.evidence());
            top.add(numbered);
            text.put(numbered.id(), causeText(numbered));
        }

        // 템플릿 문장용
        Cause first = top.get(0);
        String topItem = first.item();
        String topColumn = topItem == null ? null : items.stream().filter(i -> camel(i).equals(topItem)).findFirst().orElse(null);
        Limit topLimit = topColumn == null ? null : limits.get(topColumn);
        String topLabel = topColumn == null ? null : topLimit != null ? topLimit.label() : topColumn;
        String topRange = topLimit == null || (topLimit.lower() == null && topLimit.upper() == null) ? null
                : range(topLimit.lower(), topLimit.upper(), topLimit.unit() == null ? "" : topLimit.unit());
        Hints hints = new Hints(topLabel, topRange, topColumn == null ? null : trendIdByItem.get(topColumn), historyId, lastMemo, alarmIds,
                hasProcessData);
        return new Collected(inc, from, to, List.copyOf(ev), List.copyOf(top), text, hints);
    }

    // ── 3. 문장 쓰기 ──

    /** 모은 근거로 문장을 써서 원인 추적을 완성해요. LLM을 못 쓰면 템플릿 문장 */
    public CauseTrace write(Collected c) {
        List<Sentence> raw = null;
        String fallback = null;
        String model = null;
        if (!ai.mockMode()) {
            try {
                LlmSentences llm = llmSentences(c);
                raw = llm.sentences();
                model = llm.model();
            } catch (Exception e) {
                log.warn("원인 추적 문장 LLM 실패, 템플릿으로 대체합니다: {}", e.getMessage());
                fallback = e instanceof AiReportService.LlmUnavailableException ? e.getMessage() : "LLM 응답 형식이 올바르지 않습니다";
            }
        }
        boolean template = raw == null;
        if (template) raw = templateSentences(c);
        List<Sentence> checked = verify(raw, c.sourceText());
        int ok = (int) checked.stream().filter(Sentence::verified).count();
        return new CauseTrace(Kst.of(c.from()), Kst.of(c.to()), c.evidence(), c.causes(), checked, ok,
                template ? "mock-template" : model, template, fallback);
    }

    private LlmSentences llmSentences(Collected c) throws Exception {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("incident", Map.of("equipment", c.incident().equipmentName(), "reason", c.incident().codeName(),
                "startedAt", HMS.format(c.incident().start())));
        facts.put("evidence", c.evidence().stream().map(e -> Map.of("id", e.id(), "type", e.type(), "title", e.title(), "detail", e.detail())).toList());
        facts.put("causes", c.causes().stream().map(x -> Map.of("id", x.id(), "label", x.label(), "score", x.score(), "grade", x.grade(),
                "parts", x.parts(), "evidence", x.evidence())).toList());
        LlmClient.Reply reply = ai.chat(SYSTEM_PROMPT, objectMapper.writeValueAsString(facts));
        JsonNode root = objectMapper.readTree(reply.text());
        List<Sentence> out = new ArrayList<>();
        for (JsonNode n : root.path("sentences")) {
            String t = n.path("text").asText("").trim();
            if (t.isEmpty()) continue;
            List<String> ids = new ArrayList<>();
            n.path("evidence").forEach(x -> ids.add(x.asText().trim().toUpperCase()));
            out.add(new Sentence(t.length() > 300 ? t.substring(0, 300) : t, n.path("kind").asText("fact"), ids, false, null));
            if (out.size() >= MAX_SENTENCES) break;
        }
        if (out.isEmpty()) throw new IllegalStateException("LLM이 문장을 주지 않았습니다");
        return new LlmSentences(out, reply.model());
    }

    /** LLM 문장과 실제로 답한 모델 */
    private record LlmSentences(List<Sentence> sentences, String model) {}

    /** 근거로 만든 문장 (시연 모드·LLM 실패) */
    static List<Sentence> templateSentences(Collected c) {
        List<Sentence> out = new ArrayList<>();
        Map<String, Evidence> byId = new HashMap<>();
        c.evidence().forEach(e -> byId.put(e.id(), e));
        Evidence fault = c.evidence().get(0);
        out.add(s(c.incident().equipmentName() + " 설비가 " + HMS.format(c.incident().start()) + "에 " + c.incident().codeName() + " 사유로 멈췄습니다.",
                "fact", fault.id()));
        for (String id : c.hints().alarmIds().stream().limit(2).toList()) {
            Evidence e = byId.get(id);
            out.add(s("고장 전 " + e.title() + "이 있었습니다: " + e.detail() + ".", "fact", id));
        }
        if (c.hints().trendId() != null) {
            Evidence e = byId.get(c.hints().trendId());
            out.add(s(e.title() + " 기록: " + e.detail() + ".", "fact", e.id()));
        } else if (!c.hints().hasProcessData()) {
            c.evidence().stream().filter(e -> "TREND".equals(e.type())).findFirst()
                    .ifPresent(e -> out.add(s("고장 전 1시간 동안 수집된 공정값이 없어 공정값으로는 원인을 좁힐 수 없습니다.", "fact", e.id())));
        }
        Evidence history = byId.get(c.hints().historyId());
        out.add(s("과거 이력: " + history.detail() + ".", "fact", history.id()));

        Cause top = c.causes().get(0);
        List<String> cites = new ArrayList<>(List.of(top.id()));
        cites.addAll(top.evidence());
        out.add(new Sentence("근거가 가장 강한 원인 후보는 ‘" + top.label() + "’이며 근거 강도는 " + gradeWord(top.grade()) + "(" + top.score() + "점)입니다.",
                "inference", cites, false, null));
        if (!"HIGH".equals(top.grade())) {
            out.add(new Sentence("근거 강도가 높지 않아 원인을 단정할 수 없으며 현장 확인이 필요합니다.", "inference", List.of(top.id()), false, null));
        }
        if (c.hints().topItemLabel() != null) {
            List<String> ids = new ArrayList<>(List.of(top.id()));
            ids.addAll(top.evidence());
            out.add(new Sentence(c.hints().topItemLabel() + " 측정 센서와 관련 기구부를 먼저 점검하고, 재가동 뒤 값이 "
                    + (c.hints().topItemRange() == null ? "기준" : "기준 " + c.hints().topItemRange()) + " 안에 드는지 확인하세요.",
                    "action", ids, false, null));
        } else if (top.label().startsWith("통신")) {
            out.add(new Sentence("통신 배선·커넥터와 PLC 통신 상태를 먼저 점검하세요.", "action", List.of(top.id()), false, null));
        } else {
            out.add(new Sentence("관리값에서 뚜렷한 이상이 보이지 않으므로 모터·구동부·전기 계통과 안전장치를 먼저 점검하세요.", "action",
                    List.of(top.id(), fault.id()), false, null));
        }
        if (c.hints().lastMemo() != null) {
            out.add(s("지난 고장의 조치 메모를 참고하세요: “" + c.hints().lastMemo() + "”", "action", history.id()));
        }
        return out;
    }

    // ── 4. 검증 ──

    /** 장비·근거 ID 안의 숫자(PR3, E2, C-PR3)는 숫자로 보지 않아요 */
    private static final Pattern ID_TOKEN = Pattern.compile("[A-Za-z]+(?:-[A-Za-z]+)*\\d+");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * 문장마다 근거 번호가 있는지, 그 번호가 실제 근거인지, 문장 속 숫자가 인용한 근거 글에 있는지 확인해요.
     * 1~5 같은 작은 정수(순서·개수 말)는 그냥 통과시켜요.
     */
    static List<Sentence> verify(List<Sentence> sentences, Map<String, String> sourceText) {
        List<Sentence> out = new ArrayList<>();
        for (Sentence s : sentences) {
            String kind = switch (s.kind() == null ? "" : s.kind().trim().toLowerCase()) {
                case "inference" -> "inference";
                case "action" -> "action";
                default -> "fact";
            };
            List<String> ids = s.evidence() == null ? List.of() : s.evidence().stream().distinct().toList();
            String issue = null;
            if (ids.isEmpty()) {
                issue = "근거 번호 없음";
            } else {
                List<String> unknown = ids.stream().filter(id -> !sourceText.containsKey(id)).toList();
                if (!unknown.isEmpty()) {
                    issue = "없는 근거 번호: " + String.join(", ", unknown);
                } else {
                    List<Double> allowed = new ArrayList<>();
                    ids.forEach(id -> allowed.addAll(numbers(sourceText.get(id))));
                    List<String> bad = new ArrayList<>();
                    for (Double x : numbers(s.text())) {
                        boolean smallInt = x == Math.rint(x) && x >= 0 && x <= 5;
                        if (!smallInt && allowed.stream().noneMatch(a -> Math.abs(a - x) <= Math.max(1e-6, Math.abs(a) * 1e-4))) {
                            bad.add(fmt(x));
                        }
                    }
                    if (!bad.isEmpty()) issue = "근거에 없는 숫자: " + String.join(", ", bad);
                }
            }
            out.add(new Sentence(s.text(), kind, ids, issue == null, issue));
        }
        return out;
    }

    static List<Double> numbers(String text) {
        if (text == null) return List.of();
        String t = ID_TOKEN.matcher(text.replaceAll("(?<=\\d),(?=\\d{3})", "")).replaceAll(" ");
        List<Double> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(t);
        while (m.find()) out.add(Double.parseDouble(m.group()));
        return out;
    }

    // ── 점수 도우미 ──

    static String grade(int score) {
        return score >= HIGH ? "HIGH" : score >= MEDIUM ? "MEDIUM" : "LOW";
    }

    private static String gradeWord(String grade) {
        return switch (grade) {
            case "HIGH" -> "높음";
            case "MEDIUM" -> "보통";
            default -> "낮음";
        };
    }

    private static Cause cause(String label, String item, List<ScorePart> parts, List<String> evidence) {
        int score = parts.stream().mapToInt(ScorePart::points).sum();
        return new Cause(null, label, item, score, grade(score), List.copyOf(parts), evidence.stream().filter(x -> x != null).distinct().toList());
    }

    private static ScorePart proximity(LocalDateTime signal, LocalDateTime fault) {
        if (signal == null) return new ScorePart("시간 근접", 8, 20, "직전 10분 평균 기준 (특정 시점 없음)");
        long m = minutesBefore(signal, fault);
        int p = m <= 5 ? 20 : m <= 15 ? 14 : m <= 30 ? 8 : 0;
        return new ScorePart("시간 근접", p, 20, m == 0 ? "마지막 신호가 고장 1분 안" : "마지막 신호가 고장 " + m + "분 전");
    }

    private static ScorePart completeness(int n10, int n, int dc) {
        if (n10 >= 3 && dc == 0) return new ScorePart("데이터 완전성", 10, 10, "직전 10분 공정값 " + n10 + "점, 통신 끊김 없음");
        if (n10 > 0 || n > 0) return new ScorePart("데이터 완전성", 5, 10, "직전 10분 공정값 " + n10 + "점" + (dc > 0 ? ", 통신 끊김 " + dc + "회" : ""));
        return new ScorePart("데이터 완전성", 0, 10, "직전 1시간 공정값 없음");
    }

    private static String causeText(Cause c) {
        StringBuilder b = new StringBuilder(c.label()).append(" ").append(c.score()).append("점");
        c.parts().forEach(p -> b.append(" ").append(p.label()).append(" ").append(p.points()).append("/").append(p.max()).append(" ").append(p.note()));
        return b.toString();
    }

    // ── 조회 도우미 ──

    private Incident incident(long downtimeId) {
        List<Incident> found = jdbc.query("SELECT d.downtime_id, d.equipment_id, e.equipment_name, e.process_id, e.seq_no, d.code, c.code_name,"
                        + " d.detail, d.started_at, d.ended_at FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                        + " LEFT JOIN downtime_code c ON c.code = d.code WHERE d.downtime_id = :id", Map.of("id", downtimeId),
                (rs, n) -> new Incident(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5) == null ? null : rs.getInt(5), rs.getString(6),
                        rs.getString(7) == null ? rs.getString(6) : rs.getString(7), rs.getString(8),
                        Kst.local(rs.getObject(9)), rs.getObject(10) == null ? null : Kst.local(rs.getObject(10))));
        if (found.isEmpty()) throw new IllegalArgumentException("비가동 이력 " + downtimeId + "가 없습니다");
        return found.get(0);
    }

    private Map<String, Limit> limits(String equipmentId) {
        Map<String, Limit> m = new HashMap<>();
        jdbc.query("SELECT item_name, item_label, unit, lower_limit, upper_limit FROM threshold WHERE equipment_id = :eq",
                Map.of("eq", equipmentId), rs -> {
                    m.put(rs.getString(1), new Limit(rs.getString(2), rs.getString(3), num(rs.getBigDecimal(4)), num(rs.getBigDecimal(5))));
                });
        return m;
    }

    private static String tableOf(Incident inc) {
        return TABLE.getOrDefault(inc.processId(), "-");
    }

    /** 공정값 통계를 stats에 담고, 직전 10분 [불량 수, 전체 수]를 돌려줘요. 수집 테이블이 없는 설비(검사기)는 null */
    private int[] processStats(Incident inc, LocalDateTime from, LocalDateTime to, Map<String, Limit> limits, List<ItemStat> stats) {
        String table = TABLE.get(inc.processId());
        if (table == null) return null;
        boolean press = "PRESS".equals(inc.processId());
        List<String> cols;
        if (press) {
            List<String> stages = COLUMNS.get("PRESS");
            if (inc.seqNo() == null || inc.seqNo() < 1 || inc.seqNo() > stages.size()) return null;
            cols = List.of(stages.get(inc.seqNo() - 1));
        } else {
            List<String> withLimit = COLUMNS.get(inc.processId()).stream().filter(limits::containsKey).toList();
            cols = withLimit.isEmpty() ? COLUMNS.get(inc.processId()) : withLimit;
        }
        String select = String.join(", ", cols.stream().map(c -> "x." + c).toList());
        Map<String, Object> p = Map.of("eq", inc.equipmentId(), "a", from, "b", to);
        List<Map<String, Object>> rows = jdbc.queryForList(press
                ? "SELECT f.ended_at AS t, x.result, " + select + " FROM press_data x JOIN product_flow f ON f.product_id = x.product_id"
                        + " AND f.equipment_id = :eq WHERE x." + cols.get(0) + " IS NOT NULL AND f.ended_at >= :a AND f.ended_at < :b ORDER BY f.ended_at"
                : "SELECT x.measured_at AS t, x.result, " + select + " FROM " + table + " x WHERE x.equipment_id = :eq"
                        + " AND x.measured_at >= :a AND x.measured_at < :b ORDER BY x.measured_at", p);
        LocalDateTime recentFrom = to.minus(RECENT);
        int ng = 0, n10 = 0;
        for (Map<String, Object> r : rows) {
            if (Kst.local(value(r, "t")).isBefore(recentFrom)) continue;
            n10++;
            if ("NG".equals(value(r, "result"))) ng++;
        }
        for (String col : cols) {
            Limit l = limits.get(col);
            double sum = 0, sum10 = 0, sumBase = 0;
            int n = 0, c10 = 0, cBase = 0, out = 0;
            LocalDateTime lastOut = null;
            for (Map<String, Object> r : rows) {
                Double v = num(value(r, col));
                if (v == null) continue;
                LocalDateTime t = Kst.local(value(r, "t"));
                n++;
                sum += v;
                if (t.isBefore(recentFrom)) { cBase++; sumBase += v; continue; }
                c10++;
                sum10 += v;
                if (l != null && ((l.upper() != null && v > l.upper()) || (l.lower() != null && v < l.lower()))) {
                    out++;
                    lastOut = t;
                }
            }
            Double recentAvg = c10 == 0 ? null : sum10 / c10;
            stats.add(new ItemStat(col, l, n, c10, n == 0 ? null : sum / n, recentAvg, cBase == 0 ? null : sumBase / cBase,
                    l == null ? null : riskPercent(recentAvg, l.lower(), l.upper()), out, lastOut));
        }
        return new int[] {ng, n10};
    }

    /** 위험도 %: 가운데에서 한계까지 간 비율. 하한이 없으면 0에서 상한까지 (예지보전 화면과 같은 식) */
    static Integer riskPercent(Double avg, Double lower, Double upper) {
        if (avg == null || upper == null) return null;
        if (lower == null) return upper > 0 ? (int) Math.round(avg / upper * 100) : null;
        if (upper <= lower) return null;
        return (int) Math.round(Math.abs(avg - (lower + upper) / 2) / ((upper - lower) / 2) * 100);
    }

    // ── 작은 도우미 ──

    private static String add(List<Evidence> ev, Map<String, String> text, String type, String title, String detail, LocalDateTime at, String source) {
        String id = "E" + (ev.size() + 1);
        ev.add(new Evidence(id, type, title, detail, Kst.of(at), source));
        text.put(id, title + " " + detail + " " + HMS.format(at) + " " + MDHM.format(at));
        return id;
    }

    private static Sentence s(String text, String kind, String id) {
        return new Sentence(text, kind, List.of(id), false, null);
    }

    /** H2는 컬럼 이름을 대문자로 줘요 */
    private static Object value(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v : row.get(key.toUpperCase());
    }

    private static Double num(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b.doubleValue();
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    static String fmt(Double v) {
        if (v == null) return "-";
        BigDecimal b = BigDecimal.valueOf(v).setScale(Math.abs(v) < 100 ? 2 : 1, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return b.toPlainString();
    }

    private static String range(Double lo, Double hi, String unit) {
        if (lo == null) return "상한 " + fmt(hi) + unit;
        if (hi == null) return "하한 " + fmt(lo) + unit;
        return fmt(lo) + "~" + fmt(hi) + unit;
    }

    private static long minutesBefore(LocalDateTime at, LocalDateTime fault) {
        return Math.max(0, Duration.between(at, fault).toMinutes());
    }

    private static String formatDuration(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 60) return s + "초";
        if (s < 3600) return (s / 60) + "분 " + (s % 60) + "초";
        return (s / 3600) + "시간 " + (s % 3600 / 60) + "분";
    }

    private static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return b.toString();
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
