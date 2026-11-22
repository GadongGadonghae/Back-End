package com.gadong.sfaas.availability.service;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gadong.sfaas.auth.entity.User;
import com.gadong.sfaas.auth.repository.UserRepository;
import com.gadong.sfaas.availability.dto.PlanningDtos;
import com.gadong.sfaas.availability.dto.PlanningDtos.OrderCreateRequest;
import com.gadong.sfaas.availability.dto.PlanningDtos.ApplyResponse;
import com.gadong.sfaas.availability.dto.PlanningDtos.PlanRequest;
import com.gadong.sfaas.availability.dto.PlanningDtos.ReassignmentDetailResponse;
import com.gadong.sfaas.availability.dto.PlanningDtos.ReassignmentDetailResponse.OrderInfo;
import com.gadong.sfaas.availability.dto.PlanningDtos.ReassignmentDetailResponse.AiContent;
import com.gadong.sfaas.availability.dto.PlanningDtos.ReassignmentDetailResponse.Target;
import com.gadong.sfaas.availability.entity.ReassignmentDetail;
import com.gadong.sfaas.availability.entity.ReassignmentReport;
import com.gadong.sfaas.availability.repository.ReassignmentDetailRepository;
import com.gadong.sfaas.availability.repository.ReassignmentReportRepository;
import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.report.AiReportRequest;
import com.gadong.sfaas.report.AiReportResponse;
import com.gadong.sfaas.report.AiReportService;
import com.gadong.sfaas.report.CauseTrace;
import com.gadong.sfaas.report.CauseTraceService;
import com.gadong.sfaas.report.OrderRequestParser;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계획수정(재배정) 보고서 (R-02 조회 · R-03 만들기). 프론트 FN-009 계산 규칙 그대로예요.
 * <ul>
 *   <li>기준 시각: 오늘 고장이면 만드는 지금, 지난 날 고장이면 고장 시작 시각. 남은 시간 = 기준 시각 ~ 그날 근무 끝(plant.shift-end, 기본 24:00)</li>
 *   <li>실적 = 기준 시각까지 출고 양품 수 (inspection_result OK, G-01과 같음)</li>
 *   <li>line: 라인 안 설비는 직렬이라 라인 전체가 멈춘 것으로 봐요. 부족 = 그 라인 목표 − 실적.
 *       다른 라인(지금 고장 설비가 없는 라인)의 남은 능력 = 남은 시간 ÷ 라인 CT − 남은 계획</li>
 *   <li>paint: 도장 1대 고장 → 다른 도장 1대가 맡아요. 도장 계획 = 그 도장을 쓰는 라인(current_paint_id) 목표 합</li>
 *   <li>vision: 비전 검사기는 1대뿐이라 대신할 설비가 없어요. 부족 = 공장 목표 − 실적, 전부 미달</li>
 *   <li>나누기: 부족 수량을 남은 능력 비율대로 (내림 후 나머지는 소수점 큰 쪽부터). 계산과 문장은 AiReportService가 해요</li>
 * </ul>
 * target(11/20): 고장 건 없이 오늘 생산 목표 기준. 계획정지(S) 중인 라인의 남은 계획을 가동 중인 라인에 나눠요 (목표 변동 → 생산관리 팀장이 작성).
 * 만들 때는 제안만 해요. 공장장이 승인(R-04)하면 그때 적용해요: line·target = 라인별 생산 목표, paint = 도장 경로 전환 (11/20).
 */
@Service
@Transactional(readOnly = true)
public class ReplanService {
    private static final Logger log = LoggerFactory.getLogger(ReplanService.class);

    /** 재배정 후보 라인 상태. AiReportService는 이 상태만 후보로 써요 (지금 고장(D)인 라인은 미리 뺌) */
    private static final String CANDIDATE = "R";
    private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate jdbc;
    private final ReassignmentReportRepository reportRepository;
    private final ReassignmentDetailRepository detailRepository;
    private final UserRepository userRepository;
    private final AiReportService aiReportService;
    private final CauseTraceService causeTraceService;
    private final OrderRequestParser orderParser;
    private final PlanningService planningService;
    private final LineCommandPublisher lineCommands;
    private final ObjectMapper objectMapper;
    private final com.gadong.sfaas.monitoring.service.MonitoringService monitoringService;

    /** 근무 끝 시각 (11/21, plant.shift-end, 예: "18:00"). 남은 생산능력·납기를 이 시각까지로 계산해요. "24:00"이면 자정 */
    @org.springframework.beans.factory.annotation.Value("${plant.shift-end:24:00}")
    private String shiftEnd = "24:00";

    /** 도장 운영 판단 (11/21): 보고서에 결재 뒤 도장 운영을 적고, 결재하면 함께 적용해요. 없으면(테스트) 건너뛰어요 */
    private PaintPlanService paintPlan;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPaintPlan(PaintPlanService paintPlan) {
        this.paintPlan = paintPlan;
    }

    /** 조치 목록 끝에 "도장 운영: …" 한 줄 (결재 뒤 가동 라인 기준, 바꿀 것이 없으면 그대로) */
    private List<String> withPaint(List<String> actions, java.util.Collection<String> runningAfter) {
        if (paintPlan == null || runningAfter == null) return actions;
        String t = paintPlan.previewText(List.copyOf(runningAfter));
        if (t == null) return actions;
        List<String> out = new ArrayList<>(actions == null ? List.of() : actions);
        out.add(t);
        return out;
    }

    /**
     * 결재·적용 뒤 도장 운영 다시 맞추기 (11/21). 지금 트랜잭션이 확정(커밋)된 뒤 새 트랜잭션으로 적용해요:
     * 결재가 켜고 끈 라인을 본 다음 판단하고, 도장 쪽이 실패해도 결재는 그대로예요 (로그로만 남겨요).
     * 돌려주는 문장 = 같은 트랜잭션에서 미리 계산한 "적용할 내용" (응답 notes용)
     */
    private List<String> applyPaint(String reason) {
        if (paintPlan == null) return List.of();
        String preview = paintPlan.previewText(null);
        Runnable run = () -> {
            try {
                paintPlan.apply(reason);
            } catch (RuntimeException e) {
                log.warn("도장 운영 적용 실패 ({}): {}", reason, e.getMessage(), e);
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            run.run();
                        }
                    });
        } else {
            run.run();
        }
        return preview == null ? List.of() : List.of(preview.replace("결재하면 함께 적용합니다.", "적용 직후 함께 맞춥니다."));
    }

    private LocalDateTime shiftEndOf(LocalDate day) {
        return "24:00".equals(shiftEnd) ? day.plusDays(1).atStartOfDay() : day.atTime(java.time.LocalTime.parse(shiftEnd));
    }

    /** 공장장 목표 변경 기록 (추가 주문 · 배정 대기, 11/20) */
    private final com.gadong.sfaas.availability.repository.TargetChangeRepository changeRepository;

    public ReplanService(NamedParameterJdbcTemplate jdbc, ReassignmentReportRepository reportRepository,
                         ReassignmentDetailRepository detailRepository, UserRepository userRepository,
                         AiReportService aiReportService, CauseTraceService causeTraceService, OrderRequestParser orderParser,
                         PlanningService planningService, LineCommandPublisher lineCommands, ObjectMapper objectMapper,
                         com.gadong.sfaas.monitoring.service.MonitoringService monitoringService,
                         com.gadong.sfaas.availability.repository.TargetChangeRepository changeRepository) {
        this.changeRepository = changeRepository;
        this.jdbc = jdbc;
        this.reportRepository = reportRepository;
        this.detailRepository = detailRepository;
        this.userRepository = userRepository;
        this.aiReportService = aiReportService;
        this.causeTraceService = causeTraceService;
        this.orderParser = orderParser;
        this.planningService = planningService;
        this.lineCommands = lineCommands;
        this.objectMapper = objectMapper;
        this.monitoringService = monitoringService;
    }

    /** 고장 건 정보 */
    private record Fault(long downtimeId, String equipmentId, String equipmentName, String process, String lineId, String status,
                         String code, String codeName, String detail, LocalDateTime start) {}

    /** 계산 결과 한 줄: 대상(라인 또는 도장 설비), 남은 계획, 남은 능력 */
    private record Row(String target, int remainingPlan, int capacity) {}

    // ── R-03 만들기 ──

    /** R-03 진입점: basis = "target"이면 목표 기준 보고서, 아니면 고장 건 보고서 */
    @Transactional
    public ReassignmentDetailResponse create(Long downtimeId, String additionalRequest, String basis) {
        return "target".equalsIgnoreCase(basis) ? createForTarget(additionalRequest) : create(downtimeId, additionalRequest);
    }

    /**
     * 목표 변동 보고서 (11/20): 오늘 목표 기준. 계획정지(S) 중인 라인 = 멈춘 쪽, 그 남은 계획을 가동 중인 라인에 남은 능력 비율대로.
     * 공장장이 목표를 올리면 라인별로 똑같이 나눠 저장돼서(설정 화면), 멈춘 라인 몫을 가동 라인이 맡는 안을 만들어요
     */
    @Transactional
    public ReassignmentDetailResponse createForTarget(String additionalRequest) {
        String request = additionalRequest == null || additionalRequest.isBlank() ? null : additionalRequest.trim();
        if (request != null && request.length() > 500) throw invalid("추가 요청은 500자까지 쓸 수 있습니다");
        LocalDateTime now = Kst.now();
        LocalDate day = now.toLocalDate();
        long remainingSec = Math.max(0, Duration.between(now, shiftEndOf(day)).getSeconds());
        Map<String, Integer> targets = targets(day);
        if (targets.isEmpty()) throw invalid("오늘 생산 목표가 없습니다. 설정 > 생산 목표에서 먼저 정해 주세요");
        int plantTarget = targets.values().stream().mapToInt(Integer::intValue).sum();
        List<String> stopped = stoppedLines();
        BigDecimal ct = cycleTime("'SLITTING', 'PRESS', 'WELDING'");
        int stoppedPlan = stopped.stream().mapToInt(l -> targets.getOrDefault(l, 0)).sum();
        int stoppedDone = stopped.stream().mapToInt(l -> done(day, now, l, null)).sum();
        List<Row> rows = new ArrayList<>();
        for (String line : jdbc.queryForList("SELECT line_id FROM line ORDER BY line_id", Map.of(), String.class)) {
            if (stopped.contains(line) || downNow(null, line)) continue;
            int remainingPlan = Math.max(0, targets.getOrDefault(line, 0) - done(day, now, line, null));
            rows.add(new Row(line, remainingPlan, capacity(remainingSec, ct, remainingPlan)));
        }
        String stoppedKey = stopped.isEmpty() ? "-" : String.join("·", stopped);
        String reason = "오늘 생산 목표 " + plantTarget + "개" + (stopped.isEmpty() ? "" : ", 계획정지 라인 " + stoppedKey);
        AiReportResponse ai = aiReportService.generate(new AiReportRequest(
                0L, stoppedKey, "TARGET", "생산 목표 변경", reason, Kst.of(now), stoppedPlan, stoppedDone, 0, shiftEnd,
                rows.stream().map(r -> new AiReportRequest.LineCondition(r.target(), CANDIDATE, r.capacity(), r.remainingPlan(), null)).toList(),
                "오늘 공장 생산 목표가 " + plantTarget + "개로 바뀜. 목표는 라인마다 똑같이 나눠 저장되는데 "
                        + (stopped.isEmpty() ? "계획정지 라인이 없어 모든 라인이 자기 몫을 만듦."
                        : stoppedKey + "라인은 계획정지 중이라 그 남은 계획을 가동 중인 라인(lineConditions)이 남은 능력 비율대로 나눠 맡는 안.")
                        + " 기준 시각 " + now.toLocalTime().withNano(0) + ", 근무 끝(" + shiftEnd + ")까지 " + remainingSec / 60 + "분, CT " + ct.stripTrailingZeros().toPlainString()
                        + "초. availableCapacity = 남은 시간 ÷ CT − 남은 계획. 수량은 allocation 값을 그대로 쓰고, 승인 전에는 계획을 바꾸지 않음.",
                request));
        String title = ai.mock() ? "생산 목표 변경에 따른 생산계획 수정 제안" : ai.title();
        String markdown = ai.mock() && ai.markdown() != null ? ai.markdown().replace(ai.title(), title) : ai.markdown();
        Map<String, Integer> add = new HashMap<>();
        ai.allocations().forEach(a -> add.put(a.lineId(), a.quantity()));
        int shortage = ai.calculatedShortage();
        int assigned = add.values().stream().mapToInt(Integer::intValue).sum();
        User user = currentUser();
        ReassignmentReport saved = reportRepository.save(new ReassignmentReport(null, shortage, now,
                user == null ? null : user.getUserId(), "target", now, ct, stoppedPlan, stoppedDone, plantTarget,
                Math.max(0, shortage - assigned), json(new AiContent(title, ai.summary(), ai.actions(), ai.risks(), markdown,
                        ai.model(), ai.mock(), ai.fallbackReason(), request, ai.requestInterpretation(), null, null))));   // 목표 보고서: 원인 추적·주문 정보 없음
        for (Row r : rows) {
            detailRepository.save(new ReassignmentDetail(saved.getReportId(), r.target(), r.capacity(), add.getOrDefault(r.target(), 0),
                    r.remainingPlan()));
        }
        detailRepository.flush();
        return toResponse(saved);
    }

    /** 지금 계획정지(S) 중인 라인 (라인 설비에 끝나지 않은 S 비가동이 있음) */
    private List<String> stoppedLines() {
        return jdbc.queryForList("SELECT DISTINCT e.line_id FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                + " WHERE d.status = 'S' AND d.ended_at IS NULL AND e.line_id IS NOT NULL ORDER BY e.line_id", Map.of(), String.class);
    }

    /**
     * 고장(D) 건으로 AI 계획수정 보고서를 만들어 저장해요. 누를 때마다 새 보고서예요 (추가 요청을 바꿔 다시 만들 수 있게).
     * additionalRequest: "A라인은 제외", "F라인 정지", "납기 우선" 같은 요청. 제외·정지 라인은 배정에서 빠지고, 정지 라인의 남은 계획은 부족 수량에 더해져요
     */
    @Transactional
    public ReassignmentDetailResponse create(Long downtimeId, String additionalRequest) {
        if (downtimeId == null) throw invalid("downtimeId가 필요합니다");
        String request = additionalRequest == null || additionalRequest.isBlank() ? null : additionalRequest.trim();
        if (request != null && request.length() > 500) throw invalid("추가 요청은 500자까지 쓸 수 있습니다");

        Fault f = fault(downtimeId);
        if (!"D".equals(f.status())) throw invalid("계획수정 보고서는 고장(D) 건만 만듭니다");

        LocalDateTime now = Kst.now();
        LocalDate day = f.start().toLocalDate();
        LocalDateTime base = day.equals(now.toLocalDate()) ? now : f.start();
        LocalDateTime midnight = shiftEndOf(day);   // 11/21: 근무 끝까지 (이름은 예전 그대로)
        long remainingSec = Math.max(0, Duration.between(base, midnight).getSeconds());
        Map<String, Integer> targets = targets(day);
        Integer plantTarget = targets.isEmpty() ? null : targets.values().stream().mapToInt(Integer::intValue).sum();

        String kind;
        BigDecimal ct;
        int stoppedPlan, stoppedDone;
        List<Row> rows = new ArrayList<>();
        List<String> newLines = new ArrayList<>();   // 고장 라인 보고서: 요청으로 새로 켜는 쉬던 라인 (11/21)
        List<String> onlyAsk = OrderRequestParser.onlyLines(request);   // "모두 E라인에 배정" (11/22)
        List<String> only = new ArrayList<>();       // 그중 실제로 배정할 수 있는 라인
        String stoppedKey; // AiReportService에서 빼는 멈춘 쪽 (라인 ID 또는 설비 ID)
        if ("INSPECTION".equals(f.process())) {
            kind = "vision";
            ct = cycleTime("'INSPECTION'");
            stoppedPlan = plantTarget == null ? 0 : plantTarget;
            stoppedDone = done(day, base, null, null);
            stoppedKey = f.equipmentId();
        } else if ("PAINTING".equals(f.process())) {
            kind = "paint";
            ct = cycleTime("'PAINTING'");
            Map<String, Integer> paintPlan = paintPlans(targets);
            stoppedPlan = paintPlan.getOrDefault(f.equipmentId(), 0);
            stoppedDone = done(day, base, null, f.equipmentId());
            stoppedKey = f.equipmentId();
            boolean today = base.equals(now);
            for (String pt : jdbc.queryForList("SELECT equipment_id FROM equipment WHERE process_id = 'PAINTING' AND is_active = TRUE"
                    + " AND equipment_id <> :eq ORDER BY equipment_id", Map.of("eq", f.equipmentId()), String.class)) {
                if (today && downNow(pt, null)) continue;
                int remainingPlan = Math.max(0, paintPlan.getOrDefault(pt, 0) - done(day, base, null, pt));
                rows.add(new Row(pt, remainingPlan, capacity(remainingSec, ct, remainingPlan)));
            }
        } else {
            kind = "line";
            ct = cycleTime("'SLITTING', 'PRESS', 'WELDING'");
            stoppedPlan = targets.getOrDefault(f.lineId(), 0);
            stoppedDone = done(day, base, f.lineId(), null);
            stoppedKey = f.lineId();
            boolean today = base.equals(now);
            // 11/21: 계획정지(쉬는) 라인은 후보에서 빼요. 요청에 "E라인 가동"처럼 켜라는 라인이 있으면 그 라인만 새로 켜는 후보로
            //        (준비 30분 뒤부터 생산, 결재하면 계획정지를 끝내고 가동 명령 → 현장이 실제로 생산)
            List<String> startLines = request == null ? List.of() : OrderRequestParser.rules(request).newLines();
            List<String> stopAsk = OrderRequestParser.stopLines(request);   // 정지 요청 라인은 받는 쪽에서 빼요 (AI가 그 몫도 부족으로 셈)
            long newSec = Math.max(0, remainingSec - DEFAULT_SETUP_MIN * 60L);
            List<String> others = jdbc.queryForList("SELECT line_id FROM line WHERE line_id <> :line ORDER BY line_id",
                    Map.of("line", f.lineId()), String.class);
            others.stream().filter(l -> onlyAsk.contains(l) && !stopAsk.contains(l) && !(today && downNow(null, l))).forEach(only::add);
            for (String line : others) {
                if (today && downNow(null, line)) continue;
                if (stopAsk.contains(line)) continue;
                if (!only.isEmpty() && !only.contains(line)) continue;   // 요청한 라인에만 (11/22)
                if (today && plannedStopNow(line)) {
                    if (startLines.contains(line) || only.contains(line)) {
                        rows.add(new Row(line, 0, capacity(newSec, ct, 0)));
                        newLines.add(line);
                    }
                    continue;
                }
                int remainingPlan = Math.max(0, targets.getOrDefault(line, 0) - done(day, base, line, null));
                rows.add(new Row(line, remainingPlan, capacity(remainingSec, ct, remainingPlan)));
            }
            // 11/21 가동 라인에 남는 능력이 고장 라인 부족분보다 모자라면, 쉬는(계획정지) 라인을 A부터 새로 켜는 후보로 더해요
            //       (가득 돌던 공장이면 나눌 곳이 없어 배정이 0이던 문제). 결재하면 계획정지를 끝내고 가동 명령
            int need = Math.max(0, stoppedPlan - stoppedDone);
            int have = rows.stream().mapToInt(Row::capacity).sum();
            if (today && have < need && only.isEmpty()) {
                for (String line : jdbc.queryForList("SELECT line_id FROM line WHERE line_id <> :line ORDER BY line_id",
                        Map.of("line", f.lineId()), String.class)) {
                    if (have >= need) break;
                    if (newLines.contains(line) || stopAsk.contains(line) || !plannedStopNow(line) || downNow(null, line)) continue;
                    Row r = new Row(line, 0, capacity(newSec, ct, 0));
                    rows.add(r);
                    newLines.add(line);
                    have += r.capacity();
                }
            }
        }

        // 11/21 잔업: 근무 끝까지 능력 합이 부족보다 작으면, 다 만드는 시각까지의 능력으로 나눠요 (미달로 남기지 않음)
        final long setupSec = Math.min(remainingSec, DEFAULT_SETUP_MIN * 60L);
        List<OtLine> otLines = rows.stream().map(r -> new OtLine(r.target(), newLines.contains(r.target()) ? setupSec : 0, r.remainingPlan())).toList();
        Long otEnd = "line".equals(kind) ? overtimeEnd(otLines, Math.max(0, stoppedPlan - stoppedDone), ct, remainingSec) : null;
        Map<String, Integer> otCap = otEnd == null ? null : capAt(otLines, otEnd, ct.doubleValue());
        String otNote = otEnd == null ? "" : " 근무 끝(" + shiftEnd + ")까지 남은 능력 합 " + rows.stream().mapToInt(Row::capacity).sum() + "개로는 부족 "
                + Math.max(0, stoppedPlan - stoppedDone) + "개를 다 못 만들어, 근무 뒤 잔업으로 " + base.plusSeconds(otEnd).toLocalTime().withSecond(0).withNano(0)
                + "까지 생산하는 안. availableCapacity는 잔업 끝까지 만들 수 있는 양.";

        // 요청한 라인에만 배정 (11/22): 처리 내용과 근거를 보고서(적용 규칙 · AI 문장)에 남겨요
        List<String> onlyRules = new ArrayList<>();
        if (!only.isEmpty()) {
            int lack = Math.max(0, stoppedPlan - stoppedDone);
            int inShift = rows.stream().mapToInt(Row::capacity).sum();
            String names = String.join("·", only) + "라인";
            onlyRules.add("요청에 따라 부족 " + lack + "개를 " + names + "에만 배정 (다른 라인에는 나누지 않음)");
            onlyRules.add("근거: " + names + "은 " + (newLines.isEmpty() ? "" : "준비 " + DEFAULT_SETUP_MIN + "분 뒤부터 ") + "근무 끝(" + shiftEnd + ")까지 "
                    + inShift + "개를 더 만들 수 있어, "
                    + (otEnd == null ? lack + "개를 근무 안에 모두 생산"
                    : Math.max(0, lack - inShift) + "개는 잔업으로 " + base.plusSeconds(otEnd).toLocalTime().withSecond(0).withNano(0) + "까지 생산"));
        }
        String onlyNote = onlyRules.isEmpty() ? "" : " 사용자 " + String.join(". ", onlyRules) + ". 다른 라인에도 나누자는 말은 쓰지 않음.";

        // 원인 추적: 근거는 지금 모으고(트랜잭션 안), 문장(LLM)은 재배정 문장과 동시에 써서 기다리는 시간을 늘리지 않아요.
        // 원인 추적이 실패해도 보고서는 만들어요 (causeTrace = null)
        CompletableFuture<CauseTrace> trace = traceAsync(f.downtimeId());

        // 부족 수량·나누기·문장은 AiReportService (팀원 report 패키지). 목표 = 멈춘 쪽 계획, 완료 = 기준 시각까지 실적
        // operatorNote로 계산 규칙을 LLM에 알려 줘요 (문장이 계산과 어긋나지 않게)
        AiReportResponse ai = aiReportService.generate(new AiReportRequest(
                f.downtimeId(), stoppedKey, f.equipmentId(), f.equipmentName(),
                f.codeName() + (f.detail() == null || f.detail().isBlank() ? "" : " (" + f.detail() + ")"),
                Kst.of(f.start()), stoppedPlan, stoppedDone, 0, shiftEnd,
                rows.stream().map(r -> new AiReportRequest.LineCondition(r.target(), CANDIDATE, otCap == null ? r.capacity() : otCap.get(r.target()), r.remainingPlan(),
                        newLines.contains(r.target()) ? "계획정지 중인 라인을 새로 가동 (준비 " + DEFAULT_SETUP_MIN + "분 뒤 생산)" : null)).toList(),
                note(kind, f, base, remainingSec, ct) + (newLines.isEmpty() ? ""
                        : " 계획정지 중이던 " + String.join(",", newLines) + "라인을 새로 가동해 함께 나눔 (결재하면 가동 명령). 다른 계획정지 라인에는 나누지 않음.") + otNote + onlyNote,
                request));
        AiReportResponse.RequestInterpretation interpretation = withOnly(ai.requestInterpretation(), kind, onlyAsk, only, onlyRules);
        // 템플릿 문장은 lineId 뒤에 "라인"을 붙여서, 도장·비전 보고서는 제목을 설비 이름으로 바꿔요
        String title = ai.title(), markdown = ai.markdown();
        if (ai.mock() && !"line".equals(kind)) {
            title = f.equipmentName() + " 생산계획 수정 제안";
            if (markdown != null) markdown = markdown.replace(ai.title(), title);
        }
        int shortage = ai.calculatedShortage();
        Map<String, Integer> add = new HashMap<>();
        ai.allocations().forEach(a -> add.put(a.lineId(), a.quantity()));
        int assigned = add.values().stream().mapToInt(Integer::intValue).sum();
        var ot = otEnd == null ? null : overtimeOf(rows.stream().map(r -> new int[] {r.capacity(), r.remainingPlan()}).toList(),
                rows.stream().map(Row::target).toList(), add, ct, base, shiftEndOf(day));
        String summaryOt = ai.summary() + overtimeText(ot, "근무 끝 " + shiftEnd);

        User user = currentUser();
        ReassignmentReport saved = reportRepository.save(new ReassignmentReport(f.downtimeId(), shortage, now,
                user == null ? null : user.getUserId(), kind, base, ct, stoppedPlan, stoppedDone, plantTarget,
                Math.max(0, shortage - assigned), json(new AiContent(title, summaryOt, withPaint(withOvertime(ai.actions(), ot, "근무 끝 " + shiftEnd),
                        "line".equals(kind) && day.equals(now.toLocalDate()) ? rows.stream().map(Row::target).toList() : null), ai.risks(), markdown,
                        ai.model(), ai.mock(), ai.fallbackReason(), request, interpretation, trace.join(), null))));
        for (Row r : rows) {
            detailRepository.save(new ReassignmentDetail(saved.getReportId(), r.target(), r.capacity(), add.getOrDefault(r.target(), 0),
                    r.remainingPlan()));
        }
        detailRepository.flush();
        return toResponse(saved);
    }

    /** 해석 결과에 "요청한 라인에만 배정"의 처리 내용·근거를 넣어요. 배정할 수 없는 라인이면 반영하지 못한 요청으로 (11/22) */
    private static AiReportResponse.RequestInterpretation withOnly(AiReportResponse.RequestInterpretation it, String kind, List<String> asked,
                                                                   List<String> only, List<String> rules) {
        if (it == null || asked.isEmpty()) return it;
        List<String> applied = new ArrayList<>(it.appliedRules());
        List<String> unhandled = new ArrayList<>(it.unhandled() == null ? List.of() : it.unhandled());
        if (!only.isEmpty()) {
            applied.remove("잔여 생산능력 기준 적용");
            applied.addAll(0, rules);
        }
        List<String> cant = asked.stream().filter(l -> !only.contains(l)).toList();
        if (!cant.isEmpty() && only.isEmpty()) {
            unhandled.add("line".equals(kind)
                    ? String.join("·", cant) + "라인에만 배정 (고장 라인이거나 지금 고장·정지 요청이라 받을 수 없어, 받을 수 있는 라인에 나눔)"
                    : String.join("·", cant) + "라인에만 배정 (공용 설비 고장 보고서라 라인을 지정할 수 없음)");
        }
        return new AiReportResponse.RequestInterpretation(it.excludedLines(), it.stoppedLines(), it.priority(), applied, only, unhandled);
    }

    private CompletableFuture<CauseTrace> traceAsync(long downtimeId) {
        CauseTraceService.Collected collected;
        try {
            collected = causeTraceService.collect(downtimeId);
        } catch (RuntimeException e) {
            log.warn("원인 추적 근거를 모으지 못했습니다 (downtimeId={}): {}", downtimeId, e.getMessage(), e);
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> causeTraceService.write(collected)).exceptionally(e -> {
            log.warn("원인 추적 문장을 쓰지 못했습니다 (downtimeId={}): {}", downtimeId, e.getMessage(), e);
            return null;
        });
    }

    // ── R-05 추가 주문 배정 ──

    /** 쉬던 라인을 새로 켤 때 기본 준비 시간 (분). 요청에 준비 시간이 있으면 그 값 */
    static final int DEFAULT_SETUP_MIN = 30;

    /**
     * 추가 주문을 지금 가동 중인 라인(과 새로 켤 라인)에 나누는 검토 보고서를 만들어 저장해요. 고장 건이 없어요 (downtime_id = NULL, kind = order).
     * <ul>
     *   <li>요청 문장은 OrderRequestParser가 조건으로 해석 (AI, 실패하면 규칙). 화면에서 준 칸이 있으면 그 값이 우선</li>
     *   <li>가동 중 라인: 요청에서 말한 라인, 말이 없으면 지금 계획정지(S)가 아니고 오늘 목표가 있는 라인. 지금 고장(D)인 라인은 빼요</li>
     *   <li>남은 능력: 가동 중 = (납기까지 시간 ÷ 라인 CT) − 남은 계획, 새로 켤 라인 = (납기까지 시간 − 준비 시간) ÷ 라인 CT</li>
     *   <li>새로 켤 라인을 말하지 않았는데 지금 라인으로 모자라면, 쉬는 라인을 A부터 하나씩 더해 모자람이 없어질 때까지 추천</li>
     *   <li>나누기와 문장은 고장 보고서와 같은 AiReportService (남은 능력 비율). 계획은 제안만 해요</li>
     * </ul>
     */
    @Transactional
    public ReassignmentDetailResponse createOrder(OrderCreateRequest body) {
        String text = body == null || body.request() == null ? "" : body.request().trim();
        if (text.length() > 500) throw invalid("요청은 500자까지 쓸 수 있습니다");
        OrderRequestParser.Parsed parsed = orderParser.parse(text);
        List<String> notes = new ArrayList<>(parsed.notes());

        // 공장장이 목표를 올려 생긴 추가 주문 (G-05): 이전·새 목표를 AI에 알려 주고, 수량을 안 주면 배정 대기 수량
        com.gadong.sfaas.availability.entity.TargetChange change = body == null || body.targetChangeId() == null ? null
                : changeRepository.findById(body.targetChangeId()).orElseThrow(() -> invalid("목표 변경 " + body.targetChangeId() + "을(를) 찾을 수 없습니다"));
        if (change != null && change.getClosedAt() != null) throw invalid("이미 결재됐거나 다시 바뀐 목표 변경입니다. 화면을 새로 고쳐 주세요");
        Integer pendingQty = null;
        if (change != null) {
            int sum = targets(change.getTargetDate()).values().stream().mapToInt(Integer::intValue).sum();
            if (change.getAfterQty() < sum) return createReduce(change, sum - change.getAfterQty(), text);   // 11/21 목표 감소
            pendingQty = Math.max(0, change.getAfterQty() - sum);
        }
        Integer addQty = body != null && body.addQty() != null ? body.addQty() : pendingQty != null && pendingQty > 0 ? pendingQty : parsed.addQty();
        // 11/21 정지만 요청 ("D라인 정지하고 재배정"): 추가 수량 없이 그 라인 몫만 다른 라인에 나눠요
        if ((addQty == null || addQty <= 0) && !OrderRequestParser.stopLines(text).isEmpty()) addQty = 0;
        if (addQty == null || addQty < 0 || (addQty == 0 && OrderRequestParser.stopLines(text).isEmpty()) || addQty > OrderRequestParser.MAX_QTY) {
            throw invalid("추가 주문 수량을 알 수 없습니다. 요청에 수량을 적어 주세요 (예: 추가 주문 300개)");
        }
        String dueAt = body != null && body.dueAt() != null && !body.dueAt().isBlank() ? body.dueAt().trim()
                : parsed.dueAt() != null && !"24:00".equals(parsed.dueAt()) ? parsed.dueAt() : shiftEnd;   // "오늘 안" = 근무 끝
        int setup = body != null && body.setupMinutes() != null ? body.setupMinutes()
                : parsed.setupMinutes() != null ? parsed.setupMinutes() : DEFAULT_SETUP_MIN;

        LocalDateTime now = Kst.now();
        LocalDate day = now.toLocalDate();
        LocalDateTime due;
        try {
            due = "24:00".equals(dueAt) ? day.plusDays(1).atStartOfDay() : day.atTime(java.time.LocalTime.parse(dueAt));
        } catch (java.time.format.DateTimeParseException e) {
            throw invalid("납기 시각은 HH:mm 형식입니다");
        }
        if (!due.isAfter(now)) throw invalid("납기 " + dueAt + "이(가) 이미 지났습니다");
        long remainingSec = Duration.between(now, due).getSeconds();
        BigDecimal ct = cycleTime("'SLITTING', 'PRESS', 'WELDING'");
        Map<String, Integer> targets = targets(day);
        Integer plantTarget = targets.isEmpty() ? null : targets.values().stream().mapToInt(Integer::intValue).sum();

        List<String> allLines = jdbc.queryForList("SELECT line_id FROM line ORDER BY line_id", Map.of(), String.class);
        List<String> excluded = parsed.excludedLines();
        List<String> down = allLines.stream().filter(l -> downNow(null, l)).toList();
        down.forEach(l -> notes.add(l + "라인은 지금 고장 중이라 뺐습니다"));
        List<String> onlyAsk = OrderRequestParser.onlyLines(text);   // "모두 E라인에 배정" (11/22)
        List<String> saidRunning = body != null && body.runningLines() != null && !body.runningLines().isEmpty()
                ? body.runningLines() : parsed.runningLines().stream().filter(l -> !(onlyAsk.contains(l) && plannedStopNow(l))).toList();
        List<String> running = new ArrayList<>();
        List<String> idle = new ArrayList<>();
        for (String l : allLines) {
            if (down.contains(l) || excluded.contains(l)) continue;
            boolean on = saidRunning.isEmpty() ? !plannedStopNow(l) && targets.getOrDefault(l, 0) > 0 : saidRunning.contains(l);
            (on ? running : idle).add(l);
        }
        if (saidRunning.isEmpty()) notes.add("가동 중 라인은 지금 계획정지가 아니고 오늘 목표가 있는 라인으로 봤습니다");

        List<String> saidNew = body != null && body.newLines() != null && !body.newLines().isEmpty() ? body.newLines() : parsed.newLines();
        List<String> newLines = new ArrayList<>();
        for (String l : saidNew) {
            if (running.contains(l)) notes.add(l + "라인은 이미 가동 중이라 새로 켜지 않고 그대로 계산했습니다");
            else if (idle.contains(l)) newLines.add(l);
            else notes.add(l + "라인은 고장 중이거나 제외라서 새로 켤 수 없습니다");
        }

        // 11/22 요청한 라인에만 배정: 쉬는 라인이면 새로 켜고, 다른 라인에는 나누지 않아요
        List<String> stopSaid = OrderRequestParser.stopLines(text);
        List<String> only = onlyAsk.stream().filter(l -> (running.contains(l) || idle.contains(l)) && !stopSaid.contains(l)).toList();
        if (!only.isEmpty()) {
            for (String l : only) if (idle.contains(l) && !newLines.contains(l)) newLines.add(l);
            newLines.retainAll(only);
            notes.add("요청에 따라 " + String.join("·", only) + "라인에만 배정했습니다 (다른 라인에는 나누지 않음)");
        } else if (!onlyAsk.isEmpty()) {
            notes.add(String.join("·", onlyAsk) + "라인은 고장·제외·정지라 받을 수 없어, 받을 수 있는 라인에 나눴습니다");
        }

        // 11/21 정지 요청 ("D라인 정지"): 가동 라인에서 빼고 그 라인 남은 계획까지 다른 라인이 맡아요. 결재하면 정지 명령
        List<String> stopReq = OrderRequestParser.stopLines(text).stream().filter(running::contains).toList();
        Map<String, Integer> stopRemain = new LinkedHashMap<>();
        for (String l : stopReq) {
            stopRemain.put(l, Math.max(0, targets.getOrDefault(l, 0) - done(day, now, l, null)));
            running.remove(l);
            notes.add(l + "라인은 요청대로 정지하고 남은 계획 " + stopRemain.get(l) + "개를 다른 라인에 나눕니다");
        }
        int moveQty = stopRemain.values().stream().mapToInt(Integer::intValue).sum();
        if (running.isEmpty() && newLines.isEmpty() && idle.isEmpty()) throw invalid("정지하면 남는 가동 라인이 없습니다");
        Map<String, Row> rows = new LinkedHashMap<>();
        List<String> sharing = only.isEmpty() ? running : running.stream().filter(only::contains).toList();   // 나눠 받을 가동 라인
        for (String l : sharing) {
            int remainingPlan = Math.max(0, targets.getOrDefault(l, 0) - done(day, now, l, null));
            rows.put(l, new Row(l, remainingPlan, capacity(remainingSec, ct, remainingPlan)));
        }
        int currentCap = rows.values().stream().mapToInt(Row::capacity).sum();
        // 11/21 지금 속도로 근무 끝까지 기존 계획도 못 채우는 수 (라인마다 남은 계획 − 근무 끝까지 만들 수 있는 수)
        int backlog = 0;
        for (String l : sharing) {
            int rp = rows.get(l).remainingPlan();
            backlog += Math.max(0, rp - (int) Math.floor(remainingSec / ct.doubleValue()));
        }
        int need = addQty + moveQty + backlog;   // 새 라인을 고를 때: 추가 주문 + 정지 라인 몫 + 기존 계획 부족분
        long newSec = Math.max(0, remainingSec - setup * 60L);
        boolean recommended = false;
        if (newLines.isEmpty() && only.isEmpty()) {
            // 라인을 정하지 않음: "N라인 더"면 그 수만큼, 아니면 모자랄 때만 쉬는 라인을 A부터 더해요
            int want = parsed.addLineCount() != null ? parsed.addLineCount() : 0;
            int cap = currentCap;
            for (String l : idle) {
                if (want > 0 ? newLines.size() >= want : cap >= need) break;
                newLines.add(l);
                cap += capacity(newSec, ct, 0);
            }
            recommended = !newLines.isEmpty();
        }
        for (String l : newLines) rows.put(l, new Row(l, 0, capacity(newSec, ct, 0)));
        int withCap = rows.values().stream().mapToInt(Row::capacity).sum();
        PlanningDtos.ReassignmentDetailResponse.Scenario current =
                new PlanningDtos.ReassignmentDetailResponse.Scenario(List.copyOf(running), currentCap, Math.max(0, need - currentCap));
        PlanningDtos.ReassignmentDetailResponse.Scenario withNew = new PlanningDtos.ReassignmentDetailResponse.Scenario(
                rows.keySet().stream().sorted().toList(), withCap, Math.max(0, need - withCap));
        if (rows.isEmpty()) throw invalid("나눌 수 있는 라인이 없습니다 (모두 고장·제외)");

        String changeNote = change == null ? "" : "공장장이 오늘 공장 목표를 " + change.getBeforeQty() + "개에서 " + change.getAfterQty() + "개로 올림(+"
                + (change.getAfterQty() - change.getBeforeQty()) + "개). 라인 목표에 아직 안 나눈 이 증가분이 이번 추가 주문. ";
        String backlogNote = backlog > 0 ? "지금 속도로는 가동 중 라인의 기존 계획도 " + dueAt + "까지 " + backlog + "개 모자라 새 라인이 이것까지 메워야 함 (필요 수량 " + need + "개). " : "";
        String note = changeNote + backlogNote + "추가 주문 " + addQty + "개, 납기 오늘 " + dueAt + ". 기준 시각 " + now.toLocalTime().withNano(0) + ", 납기까지 "
                + remainingSec / 60 + "분, 라인 CT " + ct.stripTrailingZeros().toPlainString() + "초. 가동 중 라인 " + String.join(",", running)
                + (newLines.isEmpty() ? "" : ", 새로 가동 " + String.join(",", newLines) + "(준비 " + setup + "분 뒤 생산)")
                + ". availableCapacity = 남은 능력, currentPlan = 남은 계획. 지금 라인만이면 남은 능력 합 " + currentCap + "개, 새 라인까지 "
                + withCap + "개. 수량은 allocation 값을 그대로 쓰고, 계획은 제안만 하며 자동으로 바꾸지 않음.";
        // 11/21 잔업: 납기까지 능력 합이 (추가 주문 + 정지 라인 몫)보다 작으면 다 만드는 시각까지 능력으로 나눠요
        final long orderSetupSec = Math.min(remainingSec, setup * 60L);
        List<OtLine> otLines = rows.values().stream().map(r -> new OtLine(r.target(), newLines.contains(r.target()) ? orderSetupSec : 0, r.remainingPlan())).toList();
        Long otEnd = overtimeEnd(otLines, addQty + moveQty, ct, remainingSec);
        Map<String, Integer> otCap = otEnd == null ? null : capAt(otLines, otEnd, ct.doubleValue());
        if (!only.isEmpty()) {
            int inTime = Math.max(0, withCap);
            notes.add("근거: " + String.join("·", only) + "라인은 납기 " + dueAt + "까지 " + inTime + "개를 더 만들 수 있어, "
                    + (otEnd == null ? (addQty + moveQty) + "개를 납기 안에 모두 생산"
                    : Math.max(0, addQty + moveQty - inTime) + "개는 잔업으로 " + now.plusSeconds(otEnd).toLocalTime().withSecond(0).withNano(0) + "까지 생산"));
            note += " 사용자 요청에 따라 " + String.join("·", only) + "라인에만 배정함. 다른 라인에도 나누자는 말은 쓰지 않음.";
        }
        if (otEnd != null) note += " 납기(" + dueAt + ")까지 능력으로는 다 못 만들어 잔업으로 " + now.plusSeconds(otEnd).toLocalTime().withSecond(0).withNano(0)
                + "까지 생산하는 안. availableCapacity는 잔업 끝까지 만들 수 있는 양.";
        AiReportResponse ai = aiReportService.generate(new AiReportRequest(
                0L, "ORDER", "ORDER", "추가 주문", (change == null ? "" : "공장 목표 " + change.getBeforeQty() + "→" + change.getAfterQty() + "개, ")
                        + "추가 주문 " + addQty + "개 (납기 오늘 " + dueAt + ")", Kst.of(now),
                addQty + moveQty, 0, 0, dueAt,
                rows.values().stream().map(r -> new AiReportRequest.LineCondition(r.target(), CANDIDATE, otCap == null ? r.capacity() : otCap.get(r.target()), r.remainingPlan(),
                        newLines.contains(r.target()) ? "새로 가동 (준비 " + setup + "분)" : null)).toList(),
                note, null));
        Map<String, Integer> add = new HashMap<>();
        ai.allocations().forEach(a -> add.put(a.lineId(), a.quantity()));
        int assigned = add.values().stream().mapToInt(Integer::intValue).sum();
        int unmet = Math.max(0, addQty + moveQty - assigned);

        String title = "추가 주문 " + addQty + "개 배정 검토" + (stopReq.isEmpty() ? "" : " · " + String.join("·", stopReq) + "라인 정지");
        String summary = ai.summary();
        List<String> actions = ai.actions();
        List<String> risks = ai.risks();
        String markdown = ai.markdown();
        if (ai.mock()) {
            // 템플릿 문장은 고장 보고서용이라 추가 주문에 맞게 다시 써요
            summary = (change == null ? "" : "공장 목표가 " + change.getBeforeQty() + "개에서 " + change.getAfterQty() + "개로 올라 ")
                    + "추가 주문 " + addQty + "개를 오늘 " + dueAt + "까지 만들기 위해 "
                    + (newLines.isEmpty() ? "가동 중인 " + running.size() + "개 라인" : String.join("·", newLines) + "라인을 새로 가동해 " + rows.size() + "개 라인")
                    + "에 " + assigned + "개를 나누는 안을 제안합니다." + (unmet > 0 ? " " + unmet + "개는 납기 안에 만들기 어렵습니다." : "");
            actions = new ArrayList<>();
            for (String l : newLines) actions.add(l + "라인 가동을 준비합니다 (준비 " + setup + "분, 금형·자재·작업자 확인).");
            for (String l : rows.keySet()) if (add.getOrDefault(l, 0) > 0) actions.add(l + "라인에 " + add.get(l) + "개를 추가 배정합니다.");
            actions.add("계획 변경 전 생산관리자 승인을 받습니다.");
            risks = new ArrayList<>();
            if (!newLines.isEmpty()) risks.add("새로 켜는 라인은 준비 시간이 " + setup + "분보다 길어지면 그만큼 생산이 줄어듭니다.");
            risks.add(unmet > 0 ? "남은 " + unmet + "개는 지금 능력으로 납기 안에 만들 수 없어 납기 협의가 필요합니다."
                    : "계산상 납기 안에 만들 수 있지만 실제 가동률에 따라 다시 계산해야 합니다.");
            markdown = null;
        }
        var ot = otEnd == null ? null : overtimeOf(rows.values().stream().map(r -> new int[] {r.capacity(), r.remainingPlan()}).toList(),
                List.copyOf(rows.keySet()), add, ct, now, due);
        if (ot != null) {
            summary = summary + overtimeText(ot, "납기 " + dueAt);
            actions = withOvertime(actions, ot, "납기 " + dueAt);
        }
        actions = withPaint(actions, rows.keySet());   // 11/21 결재 뒤 가동 라인(지금 가동 + 새로 켤 라인 − 정지 요청) 기준 도장 운영
        OrderInfo order = new OrderInfo(text.isEmpty() ? null : text, addQty, dueAt, setup, List.copyOf(running), List.copyOf(newLines), recommended,
                idle.stream().filter(l -> !newLines.contains(l)).toList(), List.copyOf(excluded), parsed.parsedBy(), List.copyOf(notes), current, withNew,
                change == null ? null : change.getBeforeQty(), change == null ? null : change.getAfterQty(), List.copyOf(stopReq));
        AiReportResponse.RequestInterpretation interpretation = new AiReportResponse.RequestInterpretation(List.copyOf(excluded), List.of(), "BALANCED",
                List.of("추가 " + addQty + "개 · 납기 " + dueAt, "가동 중 " + String.join(",", running),
                        newLines.isEmpty() ? "새 라인 없음" : "새로 가동 " + String.join(",", newLines) + (recommended ? " (계산으로 추천)" : "")));

        User user = currentUser();
        ReassignmentReport saved = reportRepository.save(new ReassignmentReport(null, addQty, now,
                user == null ? null : user.getUserId(), "order", now, ct, addQty, 0, plantTarget, unmet,
                json(new AiContent(title, summary, actions, risks, markdown, ai.model(), ai.mock(), ai.fallbackReason(),
                        text.isEmpty() ? null : text, interpretation, null, order))));
        for (Row r : rows.values()) {
            detailRepository.save(new ReassignmentDetail(saved.getReportId(), r.target(), r.capacity(), add.getOrDefault(r.target(), 0),
                    r.remainingPlan()));
        }
        for (var e : stopRemain.entrySet()) {   // 정지 라인: 남은 계획만큼 목표를 줄임 (결재 때)
            detailRepository.save(new ReassignmentDetail(saved.getReportId(), e.getKey(), 0, -e.getValue(), e.getValue()));
        }
        detailRepository.flush();
        if (change != null) change.link(saved.getReportId());
        return toResponse(saved);
    }

    /** 라인 정지 (11/21): 계획정지 시작(서버 판정은 바로 S) + 현장에 정지 명령 (MQTT {ctl}/line hold). 문제가 있으면 문구 */
    private String stopLine(String line) {
        String note = null;
        if (!plannedStopNow(line)) {
            try {
                planningService.startPlannedStop(new PlanningDtos.PlannedStopRequest(line, "PL_SHIFT", null));
            } catch (BusinessException e) {
                note = line + "라인 계획정지: " + e.getMessage();
            }
        }
        if (!lineCommands.send(line, "hold")) note = (note == null ? "" : note + " · ") + line + "라인 정지 명령을 MQTT 브로커에 보내지 못했습니다";
        return note;
    }

    /** 쉬던 라인 가동: 진행 중인 계획정지를 끝내고 현장에 가동 명령 (MQTT {ctl}/line start). 명령을 못 보내면 false */
    private boolean startLine(String line) {
        for (Long id : jdbc.queryForList("SELECT DISTINCT d.stop_group_id FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                + " WHERE e.line_id = :line AND d.status = 'S' AND d.ended_at IS NULL AND d.stop_group_id IS NOT NULL", Map.of("line", line), Long.class)) {
            planningService.endPlannedStop(id, null);
        }
        return lineCommands.send(line, "start");
    }

    /**
     * 목표 감소 보고서 (11/21): 공장장이 오늘 목표를 줄였을 때. 줄어든 수량만큼 가동 라인 계획을 줄여요.
     * 남은 계획이 줄일 수량 안에 드는 라인은 통째로 정지(뒤 라인부터), 남는 수량은 나머지 라인의 남은 계획 비율대로 줄여요.
     * 결재하면 라인 목표를 줄이고, 정지 라인은 계획정지 + 정지 명령(현장이 실제로 멈춤). 계산은 코드, 문장은 템플릿
     */
    private ReassignmentDetailResponse createReduce(com.gadong.sfaas.availability.entity.TargetChange change, int reduce, String text) {
        LocalDateTime now = Kst.now();
        LocalDate day = now.toLocalDate();
        Map<String, Integer> targets = targets(day);
        BigDecimal ct = cycleTime("'SLITTING', 'PRESS', 'WELDING'");
        long remainingSec = Math.max(0, Duration.between(now, shiftEndOf(day)).getSeconds());
        List<String> running = new ArrayList<>();
        for (String l : jdbc.queryForList("SELECT line_id FROM line ORDER BY line_id", Map.of(), String.class)) {
            if (!plannedStopNow(l) && targets.getOrDefault(l, 0) > 0) running.add(l);
        }
        if (running.isEmpty()) throw invalid("줄일 수 있는 가동 라인이 없습니다");
        Map<String, Integer> remain = new LinkedHashMap<>();
        for (String l : running) remain.put(l, Math.max(0, targets.getOrDefault(l, 0) - done(day, now, l, null)));
        // 뒤 라인부터: 남은 계획이 줄일 수량 안이면 통째로 정지 (최소 1라인은 남김)
        int left = reduce;
        List<String> stop = new ArrayList<>();
        // 요청에서 정지하라고 한 라인 먼저 (11/21). 그 라인 남은 계획이 줄일 수량보다 크면 나머지 라인이 그만큼 더 맡아요 (left < 0)
        for (String l : OrderRequestParser.stopLines(text)) {
            if (running.contains(l) && running.size() - stop.size() > 1) { stop.add(l); left -= remain.get(l); }
        }
        List<String> order = new ArrayList<>(running);
        java.util.Collections.reverse(order);
        for (String l : order) {
            if (running.size() - stop.size() <= 1 || stop.contains(l)) continue;
            if (left > 0 && remain.get(l) <= left) { stop.add(l); left -= remain.get(l); }
        }
        Map<String, Integer> add = new LinkedHashMap<>();
        for (String l : stop) add.put(l, -remain.get(l));
        List<String> keep = running.stream().filter(l -> !stop.contains(l)).toList();
        int keepSum = keep.stream().mapToInt(remain::get).sum();
        int given = 0;
        for (int i = 0; i < keep.size(); i++) {
            String l = keep.get(i);
            int cut = i == keep.size() - 1 ? left - given : (int) Math.round(left * (keepSum == 0 ? 1.0 / keep.size() : remain.get(l) / (double) keepSum));
            cut = Math.min(cut, remain.get(l));   // left < 0이면 cut < 0 = 그만큼 더 맡음
            add.put(l, -cut);
            given += cut;
        }
        int before = change.getBeforeQty(), after = change.getAfterQty();
        String title = "생산 목표 감소 " + reduce + "개 계획 조정";
        String summary = "공장 목표가 " + before + "개에서 " + after + "개로 " + reduce + "개 줄었습니다. "
                + (stop.isEmpty() ? "" : String.join("·", stop) + "라인은 남은 계획(" + stop.stream().mapToInt(remain::get).sum() + "개)이 줄어든 수량 안이라 정지하고, ")
                + (keep.isEmpty() || left == 0 ? "" : "나머지 " + String.join("·", keep) + "라인의 계획을 " + Math.abs(left) + "개 " + (left > 0 ? "줄여 " : "늘려 "))
                + "근무 끝(" + shiftEnd + ")까지의 생산을 새 목표에 맞춥니다.";
        List<String> actions = new ArrayList<>();
        for (String l : stop) actions.add(l + "라인 정지 (남은 계획 " + remain.get(l) + "개 취소, 결재하면 정지 명령)");
        for (String l : keep) if (add.get(l) != 0) actions.add(l + "라인 계획 " + Math.abs(add.get(l)) + "개 " + (add.get(l) < 0 ? "줄임" : "늘림"));
        actions = withPaint(actions, keep);   // 11/21 남는 라인 기준 도장 운영 (합치면 도장 1대 운휴)
        List<String> risks = List.of("정지한 라인을 다시 켜면 준비 시간(" + DEFAULT_SETUP_MIN + "분)이 걸립니다.",
                "이미 투입된 재공은 끝까지 처리합니다.");
        OrderInfo info = new OrderInfo(text.isEmpty() ? null : text, -reduce, shiftEnd, 0, List.copyOf(running), List.of(), false, List.of(), List.of(),
                "규칙 계산", List.of(), new PlanningDtos.ReassignmentDetailResponse.Scenario(List.copyOf(running), 0, 0),
                new PlanningDtos.ReassignmentDetailResponse.Scenario(keep, 0, 0), before, after, List.copyOf(stop));
        AiReportResponse.RequestInterpretation interpretation = new AiReportResponse.RequestInterpretation(List.of(), List.copyOf(stop), "BALANCED",
                List.of("목표 감소 " + reduce + "개", stop.isEmpty() ? "정지 라인 없음" : "정지 " + String.join(",", stop)));
        User user = currentUser();
        ReassignmentReport saved = reportRepository.save(new ReassignmentReport(null, -reduce, now,
                user == null ? null : user.getUserId(), "order", now, ct, reduce, 0, after, 0,
                json(new AiContent(title, summary, actions, risks, null, "규칙 계산", true, null,
                        text.isEmpty() ? null : text, interpretation, null, info))));
        for (String l : running) {
            detailRepository.save(new ReassignmentDetail(saved.getReportId(), l, capacity(remainingSec, ct, remain.get(l)), add.getOrDefault(l, 0), remain.get(l)));
        }
        detailRepository.flush();
        change.link(saved.getReportId());
        return toResponse(saved);
    }

    /** 그 라인에 지금 계획정지(S)가 진행 중인지 */
    private boolean plannedStopNow(String lineId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                + " WHERE d.status = 'S' AND d.ended_at IS NULL AND e.line_id = :line", Map.of("line", lineId), Long.class);
        return n != null && n > 0;
    }

    // ── R-06 추가 주문 배정 승인·적용 ──

    /**
     * 추가 주문 배정 보고서를 승인하고 실제로 적용해요 (사람이 누를 때만, 한 번만).
     * <ol>
     *   <li>새로 켤 라인의 진행 중 계획정지를 끝내요 (S-03과 같음)</li>
     *   <li>오늘 라인 목표에 배정 수량을 더해요 (G-03과 같음, 수정자 = 적용한 사람)</li>
     *   <li>보고서를 승인됨으로 표시 (approved · approved_by · approved_at)</li>
     *   <li>새 라인에 가동 명령을 보내요 (MQTT {ctl}/line start → 재생기·설비가 생산 시작 → 대시보드에 가동으로 보여요)</li>
     * </ol>
     */
    @Transactional
    public ApplyResponse apply(long reportId) {
        ReassignmentReport r = reportRepository.findById(reportId).orElseThrow(() ->
                new BusinessException(ErrorCode.REASSIGNMENT_NOT_FOUND, "재배정 보고서 " + reportId + "를 찾을 수 없습니다"));
        if (!"order".equals(r.getKind())) throw invalid("추가 주문 배정 보고서만 적용할 수 있습니다 (고장 보고서는 제안만 해요)");
        if (Boolean.TRUE.equals(r.getApproved())) throw invalid("이미 적용한 보고서입니다");
        AiContent content = ai(r.getAiContent());
        OrderInfo order = content == null ? null : content.order();
        if (order == null) throw invalid("보고서에 추가 주문 정보가 없습니다");
        LocalDateTime now = Kst.now();
        LocalDate today = now.toLocalDate();
        if (!r.getCreatedAt().toLocalDate().equals(today)) throw invalid("오늘 만든 추가 주문 보고서만 적용할 수 있습니다");
        List<String> notes = new ArrayList<>();

        // 1) 새 라인의 계획정지 끝내기
        List<Long> ended = new ArrayList<>();
        for (String line : order.newLines()) {
            List<Long> stops = jdbc.queryForList("SELECT DISTINCT d.stop_group_id FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                    + " WHERE e.line_id = :line AND d.status = 'S' AND d.ended_at IS NULL AND d.stop_group_id IS NOT NULL", Map.of("line", line), Long.class);
            for (Long id : stops) {
                planningService.endPlannedStop(id, null);
                ended.add(id);
            }
            if (stops.isEmpty()) notes.add(line + "라인은 진행 중인 계획정지가 없어 바로 가동 명령만 보냈습니다");
        }

        // 2) 오늘 목표에 배정 수량 더하기
        Map<String, Integer> targets = targets(today);
        List<ApplyResponse.TargetChange> changes = new ArrayList<>();
        for (ReassignmentDetail d : detailRepository.findByReportIdOrderByLineIdAsc(reportId)) {
            if (d.getAddedQty() == 0) continue;   // 11/21: 음수 = 목표 감소로 줄이는 수량
            int before = targets.getOrDefault(d.getLineId(), 0);
            int after = Math.max(0, before + d.getAddedQty());
            planningService.setPlan(today, new PlanRequest(d.getLineId(), after));
            changes.add(new ApplyResponse.TargetChange(d.getLineId(), before, after));
        }
        // 11/21 목표 감소: 정지할 라인 → 계획정지 시작 + 현장에 정지 명령 (MQTT {ctl}/line hold)
        for (String line : order.stopLines() == null ? List.<String>of() : order.stopLines()) {
            String n = stopLine(line);
            if (n != null) notes.add(n);
        }

        // 3) 승인 표시 · 이 보고서로 나눈 목표 변경(배정 대기)은 끝
        User user = currentUser();
        r.approve(user == null ? null : user.getUserId(), now);
        // 목표 변경으로 만든 보고서면: 늘어난 수량을 다 나눴으면 배정 대기 끝, 덜 나눴으면 남은 수량이 다시 배정 대기 (다시 계획을 고칠 수 있게)
        int sumAfter = targets.values().stream().mapToInt(Integer::intValue).sum()
                + changes.stream().mapToInt(t -> t.after() - t.before()).sum();
        changeRepository.findFirstByReportIdOrderByChangeIdDesc(reportId).ifPresent(c -> {
            if (c.getAfterQty() >= c.getBeforeQty() ? sumAfter >= c.getAfterQty() : sumAfter <= c.getAfterQty()) c.close(now);
            else {
                c.link(null);
                notes.add("공장 목표 " + c.getAfterQty() + "개와 라인 목표 합 " + sumAfter + "개가 아직 달라 다시 배정 대기로 남겼습니다");
            }
        });

        // 4) 가동 명령
        boolean sent = true;
        for (String line : order.newLines()) sent &= lineCommands.send(line, "start");
        if (!order.newLines().isEmpty() && !sent) notes.add("라인 가동 명령을 MQTT 브로커에 보내지 못했습니다. 재생기·설비에서 직접 가동해 주세요 (예: start D)");
        if (order.newLines().isEmpty() && (order.stopLines() == null || order.stopLines().isEmpty())) notes.add("켜거나 멈출 라인이 없어 목표만 바꿨습니다");
        notes.addAll(applyPaint("보고서 " + documentNo(r) + " 적용"));
        return new ApplyResponse(reportId, Kst.of(now), order.newLines(), ended, changes, !order.newLines().isEmpty() && sent, notes);
    }

    /** LLM에 주는 계산 규칙 설명 (보고서 문장의 근거) */
    private String note(String kind, Fault f, LocalDateTime base, long remainingSec, BigDecimal ct) {
        String common = " 기준 시각 " + base.toLocalTime() + ", 근무 끝(" + shiftEnd + ")까지 남은 시간 " + remainingSec / 60 + "분, CT " + ct.stripTrailingZeros().toPlainString()
                + "초. 실적은 기준 시각까지 적재 수량. lineConditions의 availableCapacity = 남은 시간 ÷ CT − 남은 계획(currentPlan). "
                + "수량은 allocation 값을 그대로 쓰고, 계획은 제안만 하며 자동으로 바꾸지 않음.";
        return switch (kind) {
            case "paint" -> f.equipmentName() + "(" + f.equipmentId() + ") 고장. 도장은 공용 설비라 라인 정지가 아니라, 같은 공정의 다른 도장 설비("
                    + "lineConditions의 lineId가 설비 ID)가 이 도장을 쓰는 라인들의 남은 계획을 맡는 안." + common;
            case "vision" -> f.equipmentName() + " 고장. 비전 검사기는 1대뿐이라 대신할 설비가 없어 부족 수량 전부가 목표 미달 예상. "
                    + "복구 시간 단축과 복구 후 검사 대기 물량 처리가 핵심." + common;
            default -> f.equipmentName() + " 고장. 라인 안 설비는 한 줄로 이어져 있어 " + f.lineId() + "라인 전체가 멈춘 것으로 보고, "
                    + "지금 고장 설비가 없는 다른 라인에 남은 능력 비율대로 나눔." + common;
        };
    }

    // ── R-07 배정 값 고치기 (생산관리 팀장, 결재 요청 전) ──

    /**
     * 보고서의 라인별 추가 배정 수량을 사람이 고쳐요 (11/20). 승인된 보고서는 못 고쳐요.
     * 표에 있는 라인만, 0 이상. 결재(승인)하면 고친 수량대로 목표에 들어가요.
     */
    @Transactional
    public ReassignmentDetailResponse editAllocations(long reportId, Map<String, Integer> addQty) {
        ReassignmentReport r = reportRepository.findById(reportId).orElseThrow(() ->
                new BusinessException(ErrorCode.REASSIGNMENT_NOT_FOUND, "재배정 보고서 " + reportId + "를 찾을 수 없습니다"));
        if (Boolean.TRUE.equals(r.getApproved())) throw invalid("승인된 보고서는 값을 고칠 수 없습니다");
        if (addQty == null || addQty.isEmpty()) throw invalid("고칠 라인과 수량이 필요합니다");
        Map<String, ReassignmentDetail> rows = new HashMap<>();
        detailRepository.findByReportIdOrderByLineIdAsc(reportId).forEach(d -> rows.put(d.getLineId(), d));
        for (Map.Entry<String, Integer> e : addQty.entrySet()) {
            ReassignmentDetail d = rows.get(e.getKey());
            if (d == null) throw invalid(e.getKey() + "라인은 이 보고서의 배정 표에 없습니다");
            Integer q = e.getValue();
            if (q == null || q < 0 || q > OrderRequestParser.MAX_QTY) throw invalid(e.getKey() + "라인 수량은 0 이상이어야 합니다");
            d.changeAddedQty(q);
        }
        detailRepository.flush();
        return toResponse(r);
    }

    // ── R-04 승인(결재) ──

    /**
     * 공장장이 보고서를 승인하고(결재) 현장에 적용해요 (11/20). 이미 승인된 보고서는 다시 적용하지 않아요.
     * <ul>
     *   <li>line·target: 추가 배정만큼 그 라인 목표(production_target)를 늘리고, 멈춘 라인(고장 라인·계획정지 라인) 목표는 그만큼 줄여요(실적 아래로는 안 내림)</li>
     *   <li>paint: 고장 도장을 쓰던 라인을 배정받은 도장으로 경로 전환 (M-09와 같음)</li>
     *   <li>vision: 대신할 설비가 없어 적용할 것 없음</li>
     * </ul>
     */
    @Transactional
    public ReassignmentDetailResponse approve(long reportId) {
        ReassignmentReport r = reportRepository.findById(reportId).orElseThrow(() ->
                new BusinessException(ErrorCode.REASSIGNMENT_NOT_FOUND, "재배정 보고서 " + reportId + "를 찾을 수 없습니다"));
        // 추가 주문 배정은 승인과 함께 계획을 바꾸므로 R-06 승인·적용으로만 승인해요 (승인만 하고 적용 안 된 상태를 만들지 않게)
        if ("order".equals(r.getKind())) throw invalid("추가 주문 배정은 '승인하고 적용'(R-06)으로 승인해요");
        User user = currentUser();
        if (!Boolean.TRUE.equals(r.getApproved())) {
            apply(r, user);
            // 11/21 라인을 멈추거나 켠 뒤 도장 대수·경로 다시 맞추기 (오늘 건만: 지난 날 보고서는 지금 운영과 상관없음)
            LocalDate repDay = (r.getBaseAt() == null ? r.getCreatedAt() : r.getBaseAt()).toLocalDate();
            if (repDay.equals(Kst.now().toLocalDate())) applyPaint("보고서 " + documentNo(r) + " 결재");
        }
        r.approve(user == null ? null : user.getUserId(), Kst.now());
        reportRepository.flush();
        return toResponse(r);
    }

    private void apply(ReassignmentReport r, User user) {
        List<ReassignmentDetail> rows = detailRepository.findByReportIdOrderByLineIdAsc(r.getReportId());
        String kind = r.getKind() == null ? "line" : r.getKind();
        LocalDate day = (r.getBaseAt() == null ? r.getCreatedAt() : r.getBaseAt()).toLocalDate();
        if ("paint".equals(kind)) {
            Fault f = fault(r.getDowntimeId());
            String to = rows.stream().filter(d -> d.getAddedQty() > 0).map(ReassignmentDetail::getLineId).findFirst()
                    .orElse(rows.isEmpty() ? null : rows.get(0).getLineId());
            if (to == null) return;
            for (String line : jdbc.queryForList("SELECT line_id FROM line WHERE current_paint_id = :pt ORDER BY line_id",
                    Map.of("pt", f.equipmentId()), String.class)) {
                monitoringService.changePaintRoute(line, new com.gadong.sfaas.monitoring.dto.MonitoringDtos.PaintRouteRequest(
                        to, "AI 보고서 승인 " + documentNo(r) + " (" + f.equipmentName() + " 고장)"));
                // 현장(재생기)에 경로 전환 명령은 M-09(changePaintRoute)가 보내요 (11/21, 한 곳에서만)
            }
            return;
        }
        if (!"line".equals(kind) && !"target".equals(kind)) return;
        // 11/21 고장 라인 보고서: 추가 배정을 받은 계획정지 라인은 요청으로 새로 켜는 라인 → 계획정지를 끝내고 가동 명령 (현장이 실제로 생산)
        if ("line".equals(kind)) {
            for (ReassignmentDetail d : rows) {
                if (d.getAddedQty() > 0 && plannedStopNow(d.getLineId())) startLine(d.getLineId());
            }
        }
        int moved = 0;
        for (ReassignmentDetail d : rows) {
            if (d.getAddedQty() <= 0) continue;
            addTarget(day, d.getLineId(), d.getAddedQty(), user);
            moved += d.getAddedQty();
        }
        // 11/21 고장 보고서에서 "F라인 정지"처럼 요청한 라인: 결재하면 실제로 정지하고, 그 라인 목표에서도 옮긴 만큼 빼요
        List<String> asked = new ArrayList<>();
        if ("line".equals(kind)) {
            // 11/21: 보고서는 "고장 라인 전체가 멈춘 것"으로 보고 나눴으니, 결재하면 고장 라인을 실제로 정지 (앞 공정까지 멈춤, 다시 켤 땐 추가 주문 가동)
            stopLine(fault(r.getDowntimeId()).lineId());
            AiContent c = ai(r.getAiContent());
            String req = c == null ? null : c.additionalRequest();
            for (String l : OrderRequestParser.stopLines(req)) {
                if (l.equals(fault(r.getDowntimeId()).lineId())) continue;
                asked.add(l);
                stopLine(l);
            }
        }
        // 멈춘 라인 목표에서 옮긴 만큼 빼요 (고장 라인 + 요청한 정지 라인, 또는 계획정지 라인들, 실적 아래로는 안 내림)
        List<String> from = new ArrayList<>("line".equals(kind) ? List.of(fault(r.getDowntimeId()).lineId()) : stoppedLinesOf(r, rows));
        from.addAll(asked);
        Map<String, Integer> targets = targets(day);
        for (String line : from) {
            if (moved <= 0) break;
            int floor = done(day, day.plusDays(1).atStartOfDay(), line, null);
            int cut = Math.min(moved, Math.max(0, targets.getOrDefault(line, 0) - floor));
            if (cut > 0) addTarget(day, line, -cut, user);
            moved -= cut;
        }
    }

    /** 목표 보고서의 멈춘 라인 = 재배정 표에 없는 라인 (만들 때 계획정지였던 라인) */
    private List<String> stoppedLinesOf(ReassignmentReport r, List<ReassignmentDetail> rows) {
        java.util.Set<String> listed = new java.util.HashSet<>();
        rows.forEach(d -> listed.add(d.getLineId()));
        return jdbc.queryForList("SELECT line_id FROM line ORDER BY line_id", Map.of(), String.class).stream()
                .filter(l -> !listed.contains(l)).toList();
    }

    /** 라인 목표에 더하기 (없으면 만들기). 바꾼 사람 = 승인한 공장장 → G-02 updatedBy */
    private void addTarget(LocalDate day, String lineId, int delta, User user) {
        Map<String, Object> p = new HashMap<>();
        p.put("day", day);
        p.put("line", lineId);
        p.put("delta", delta);
        p.put("now", Kst.now());
        p.put("by", user == null ? null : user.getUserId());
        int n = jdbc.update("UPDATE production_target SET target_qty = GREATEST(0, target_qty + :delta), created_at = :now, created_by = :by"
                + " WHERE target_date = :day AND line_id = :line", p);
        if (n == 0 && delta > 0) {
            jdbc.update("INSERT INTO production_target (target_date, line_id, target_qty, created_at, created_by)"
                    + " VALUES (:day, :line, :delta, :now, :by)", p);
        }
    }

    private static String documentNo(ReassignmentReport r) {
        return "AI-RPT-" + r.getCreatedAt().format(DOC_DATE) + "-" + String.format("%03d", r.getReportId());
    }

    // ── R-02 조회 ──

    public ReassignmentDetailResponse report(long reportId) {
        return toResponse(reportRepository.findById(reportId).orElseThrow(() ->
                new BusinessException(ErrorCode.REASSIGNMENT_NOT_FOUND, "재배정 보고서 " + reportId + "를 찾을 수 없습니다")));
    }

    private ReassignmentDetailResponse toResponse(ReassignmentReport r) {
        // 고장 건이 없는 보고서: target(생산 목표 변경)·order(추가 주문 배정) → 설비·코드 없이, 시작 = 기준 시각
        Fault f = r.getDowntimeId() == null
                ? ("target".equals(r.getKind())
                    ? new Fault(0, null, "생산 목표 변경", null, null, null, "TARGET", "생산 목표 변경", "오늘 생산 목표 변경", r.getBaseAt())
                    : new Fault(0, null, null, null, null, null, null, null, null, r.getBaseAt()))
                : fault(r.getDowntimeId());
        List<ReassignmentDetail> rows = detailRepository.findByReportIdOrderByLineIdAsc(r.getReportId());
        int capSum = rows.stream().mapToInt(ReassignmentDetail::getSpareCapacity).sum();
        List<Target> details = rows.stream().map(d -> new Target(d.getLineId(), d.getSpareCapacity(), d.getAddedQty(), d.getRemainingPlan(),
                capSum == 0 ? 0 : Math.round(d.getSpareCapacity() * 1000.0 / capSum) / 10.0)).toList();
        Long remainingSec = r.getBaseAt() == null ? null
                : Math.max(0, Duration.between(r.getBaseAt(), shiftEndOf(r.getBaseAt().toLocalDate())).getSeconds());
        String createdBy = r.getCreatedBy() == null ? null
                : userRepository.findById(r.getCreatedBy()).map(User::getUserName).orElse(null);
        String documentNo = documentNo(r);
        return new ReassignmentDetailResponse(r.getReportId(), r.getDowntimeId(), f.lineId(), r.getLossQty(), details, documentNo,
                f.equipmentId(), f.code(), f.detail(), Kst.of(f.start()), Kst.of(r.getCreatedAt()), createdBy, r.getKind(),
                Kst.of(r.getBaseAt()), remainingSec, r.getCtSec() == null ? null : r.getCtSec().doubleValue(),
                r.getStoppedPlan(), r.getStoppedDone(), r.getTargetQty(), r.getUnmetQty(), ai(r.getAiContent()),
                Boolean.TRUE.equals(r.getApproved()),
                r.getApprovedBy() == null ? null : userRepository.findById(r.getApprovedBy()).map(User::getUserName).orElse(null),
                Kst.of(r.getApprovedAt()), overtimeOf(r, rows));
    }

    // ── 잔업 (11/21) ──
    // 근무 끝(18:00, 추가 주문은 납기)까지 만들 수 있는 양보다 일이 많으면 "미달"로 끝내지 않고, 근무 뒤 잔업으로 다 만드는 시각을 계산해요.
    //  ① 라인마다 시각 T까지 만들 수 있는 양 = (T − 생산 시작) ÷ CT − 남은 계획 (생산 시작: 가동 중 = 지금, 새로 켜는 라인 = 준비 뒤)
    //  ② 근무 끝까지 능력 합 ≥ 부족이면 잔업 없음. 아니면 능력 합이 부족을 채우는 가장 이른 T를 찾아(이분 탐색) 그 T까지 능력 비율로 나눠요
    //  ③ 라인 잔업 수량 = 배정 − 근무 끝까지 남은 능력, 끝나는 시각 = 근무 끝 + 잔업 수량 × CT (밀린 계획이 있는 라인은 그만큼 더 늦게)

    /** 잔업 계산용 라인: 생산 시작(기준 시각부터 초)과 남은 계획 */
    private record OtLine(String line, long startSec, int remainingPlan) {}

    /** 시각 t(기준 시각부터 초)까지 라인마다 남는 능력 */
    private static Map<String, Integer> capAt(List<OtLine> lines, long t, double ct) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (OtLine l : lines) {
            out.put(l.line(), t <= l.startSec() ? 0 : Math.max(0, (int) Math.floor((t - l.startSec()) / ct) - l.remainingPlan()));
        }
        return out;
    }

    private static int sum(Map<String, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 근무 끝(deadlineSec)까지 qty를 다 못 만들면, 다 만드는 가장 이른 시각(기준 시각부터 초). 근무 안에 되면 null */
    private static Long overtimeEnd(List<OtLine> lines, int qty, BigDecimal ct, long deadlineSec) {
        if (lines.isEmpty() || qty <= 0) return null;
        double c = ct.doubleValue();
        if (sum(capAt(lines, deadlineSec, c)) >= qty) return null;
        long lo = deadlineSec, hi = deadlineSec + 86_400L;
        if (sum(capAt(lines, hi, c)) < qty) return hi;
        while (hi - lo > 1) {
            long mid = (lo + hi) / 2;
            if (sum(capAt(lines, mid, c)) >= qty) hi = mid;
            else lo = mid;
        }
        return hi;
    }

    /** 배정 결과의 잔업: 근무 끝까지 남은 능력보다 많이 배정한 라인 (보고서 저장 값으로 계산, 배정 값을 고쳐도 맞게) */
    private ReassignmentDetailResponse.Overtime overtimeOf(ReassignmentReport r, List<ReassignmentDetail> rows) {
        if (r.getBaseAt() == null || r.getCtSec() == null || !("line".equals(r.getKind()) || "order".equals(r.getKind()) || "target".equals(r.getKind()))) return null;
        LocalDateTime deadline = shiftEndOf(r.getBaseAt().toLocalDate());
        if ("order".equals(r.getKind())) {
            AiContent c = ai(r.getAiContent());
            String due = c == null || c.order() == null ? null : c.order().dueAt();
            if (due != null && !due.isBlank()) {
                try {
                    deadline = "24:00".equals(due) ? r.getBaseAt().toLocalDate().plusDays(1).atStartOfDay()
                            : r.getBaseAt().toLocalDate().atTime(java.time.LocalTime.parse(due));
                } catch (java.time.format.DateTimeParseException ignored) {
                    // 예전 형식이면 근무 끝
                }
            }
        }
        Map<String, Integer> add = new HashMap<>();
        for (ReassignmentDetail d : rows) add.put(d.getLineId(), d.getAddedQty());
        return overtimeOf(rows.stream().map(d -> new int[] {d.getSpareCapacity(), d.getRemainingPlan() == null ? 0 : d.getRemainingPlan()}).toList(),
                rows.stream().map(ReassignmentDetail::getLineId).toList(), add, r.getCtSec(), r.getBaseAt(), deadline);
    }

    /** caps[i] = {근무 끝까지 남은 능력, 남은 계획} */
    private static ReassignmentDetailResponse.Overtime overtimeOf(List<int[]> caps, List<String> lines, Map<String, Integer> add, BigDecimal ct,
                                                               LocalDateTime base, LocalDateTime deadline) {
        double c = ct.doubleValue();
        long deadlineSec = Math.max(0, Duration.between(base, deadline).getSeconds());
        List<ReassignmentDetailResponse.Overtime.OvertimeLine> out = new ArrayList<>();
        int qty = 0;
        long last = deadlineSec;
        for (int i = 0; i < lines.size(); i++) {
            int x = add.getOrDefault(lines.get(i), 0);
            int cap = caps.get(i)[0], plan = caps.get(i)[1];
            int over = Math.max(0, x - cap);
            if (over == 0) continue;
            // 생산 시작: 근무 끝까지 능력이 있으면 근무 끝 − (능력 + 남은 계획) × CT, 없으면(밀린 라인) 지금
            long start = cap > 0 ? Math.max(0, deadlineSec - (long) Math.ceil((cap + plan) * c)) : 0;
            long fin = Math.max(deadlineSec, start + (long) Math.ceil((plan + x) * c));
            qty += over;
            last = Math.max(last, fin);
            out.add(new ReassignmentDetailResponse.Overtime.OvertimeLine(lines.get(i), over, Kst.of(base.plusSeconds(fin))));
        }
        if (out.isEmpty()) return null;
        return new ReassignmentDetailResponse.Overtime(Kst.of(deadline), Kst.of(base.plusSeconds(last)), (int) ((last - deadlineSec + 59) / 60), qty, out);
    }

    /** 보고서 문장에 붙일 잔업 한 줄 */
    private static String overtimeText(ReassignmentDetailResponse.Overtime ot, String endLabel) {
        if (ot == null) return "";
        String until = ot.until().toLocalTime().withSecond(0).withNano(0).toString();
        return " " + endLabel + "까지는 " + ot.qty() + "개가 모자라, 잔업으로 " + until + "까지 생산할 예정입니다 (잔업 약 " + ot.minutes() + "분, "
                + ot.lines().stream().map(l -> l.lineId() + "라인 " + l.qty() + "개").collect(java.util.stream.Collectors.joining(", ")) + ").";
    }

    private static List<String> withOvertime(List<String> list, ReassignmentDetailResponse.Overtime ot, String endLabel) {
        if (ot == null) return list;
        List<String> out = new ArrayList<>(list == null ? List.of() : list);
        String until = ot.until().toLocalTime().withSecond(0).withNano(0).toString();
        out.add(endLabel + " 이후 " + until + "까지 잔업(약 " + ot.minutes() + "분)을 편성하고 인원·설비를 확인합니다.");
        return out;
    }

    // ── 조회 도우미 ──

    private Fault fault(Long downtimeId) {
        if (downtimeId == null) throw invalid("고장 건이 없는 보고서입니다");
        List<Fault> found = jdbc.query("SELECT d.downtime_id, d.equipment_id, e.equipment_name, e.process_id, e.line_id, d.status, d.code,"
                        + " c.code_name, d.detail, d.started_at"
                        + " FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                        + " LEFT JOIN downtime_code c ON c.code = d.code WHERE d.downtime_id = :id", Map.of("id", downtimeId),
                (rs, n) -> new Fault(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8) == null ? rs.getString(7) : rs.getString(8), rs.getString(9),
                        Kst.local(rs.getObject(10))));
        if (found.isEmpty()) throw new BusinessException(ErrorCode.DOWNTIME_NOT_FOUND, "비가동 이력 " + downtimeId + "를 찾을 수 없습니다");
        return found.get(0);
    }

    /** 그날 라인별 목표 (production_target). 없는 라인은 빠져요 */
    private Map<String, Integer> targets(LocalDate day) {
        Map<String, Integer> m = new HashMap<>();
        jdbc.query("SELECT line_id, target_qty FROM production_target WHERE target_date = :day", Map.of("day", day),
                rs -> { m.put(rs.getString(1), rs.getInt(2)); });
        return m;
    }

    /** 도장 설비별 계획 = 그 도장을 쓰는 라인(line.current_paint_id) 목표 합 */
    private Map<String, Integer> paintPlans(Map<String, Integer> targets) {
        Map<String, Integer> m = new HashMap<>();
        jdbc.query("SELECT line_id, current_paint_id FROM line", Map.of(), rs -> {
            String pt = rs.getString(2);
            if (pt != null) m.merge(pt, targets.getOrDefault(rs.getString(1), 0), Integer::sum);
        });
        return m;
    }

    /** 그날 0시 ~ 기준 시각 출고 양품 수. lineId·paintId를 주면 그 라인·그 도장만 */
    private int done(LocalDate day, LocalDateTime base, String lineId, String paintId) {
        Map<String, Object> p = new HashMap<>();
        p.put("from", day.atStartOfDay());
        p.put("base", base);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM inspection_result WHERE result = 'OK'"
                + " AND inspected_at >= :from AND inspected_at < :base");
        if (lineId != null) { sql.append(" AND line_id = :line"); p.put("line", lineId); }
        if (paintId != null) { sql.append(" AND paint_equipment_id = :pt"); p.put("pt", paintId); }
        Long n = jdbc.queryForObject(sql.toString(), p, Long.class);
        return n == null ? 0 : n.intValue();
    }

    /** 지금 고장(D) 중인 설비가 있는지. equipmentId 또는 lineId 중 하나 */
    private boolean downNow(String equipmentId, String lineId) {
        String where = equipmentId != null ? "d.equipment_id = :key" : "e.line_id = :key";
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                + " WHERE d.status = 'D' AND d.ended_at IS NULL AND " + where, Map.of("key", equipmentId != null ? equipmentId : lineId), Long.class);
        return n != null && n > 0;
    }

    /** 공정 표준 CT 중 가장 긴 값 = 라인 박자 (초). 공정 정보가 없으면 1초로 봐요 */
    private BigDecimal cycleTime(String processIds) {
        BigDecimal ct = jdbc.queryForObject("SELECT MAX(cycle_time_sec) FROM process WHERE process_id IN (" + processIds + ")",
                Map.of(), BigDecimal.class);
        return ct == null || ct.signum() <= 0 ? BigDecimal.ONE : ct;
    }

    /** 남은 능력 = 남은 시간 ÷ CT − 남은 계획 (0 아래면 0) */
    private static int capacity(long remainingSec, BigDecimal ct, int remainingPlan) {
        return Math.max(0, (int) Math.floor(remainingSec / ct.doubleValue()) - remainingPlan);
    }

    /** 로그인 사용자 (JWT의 loginId). 없으면 null */
    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        return userRepository.findByLoginId(auth.getName()).orElse(null);
    }

    private String json(AiContent ai) {
        try {
            return objectMapper.writeValueAsString(ai);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI 보고서 문장을 저장하지 못했습니다", e);
        }
    }

    private AiContent ai(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, AiContent.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
