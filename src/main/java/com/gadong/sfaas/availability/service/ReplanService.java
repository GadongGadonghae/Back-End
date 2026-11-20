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
 *   <li>기준 시각: 오늘 고장이면 만드는 지금, 지난 날 고장이면 고장 시작 시각. 남은 시간 = 기준 시각 ~ 그날 24:00</li>
 *   <li>실적 = 기준 시각까지 출고 양품 수 (inspection_result OK, G-01과 같음)</li>
 *   <li>line: 라인 안 설비는 직렬이라 라인 전체가 멈춘 것으로 봐요. 부족 = 그 라인 목표 − 실적.
 *       다른 라인(지금 고장 설비가 없는 라인)의 남은 능력 = 남은 시간 ÷ 라인 CT − 남은 계획</li>
 *   <li>paint: 도장 1대 고장 → 다른 도장 1대가 맡아요. 도장 계획 = 그 도장을 쓰는 라인(current_paint_id) 목표 합</li>
 *   <li>vision: 비전 검사기는 1대뿐이라 대신할 설비가 없어요. 부족 = 공장 목표 − 실적, 전부 미달</li>
 *   <li>나누기: 부족 수량을 남은 능력 비율대로 (내림 후 나머지는 소수점 큰 쪽부터). 계산과 문장은 AiReportService가 해요</li>
 * </ul>
 * target(11/20): 고장 건 없이 오늘 생산 목표 기준. 계획정지(S) 중인 라인의 남은 계획을 가동 중인 라인에 나눠요 (목표 변동 → 생산관리팀장이 작성).
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

    public ReplanService(NamedParameterJdbcTemplate jdbc, ReassignmentReportRepository reportRepository,
                         ReassignmentDetailRepository detailRepository, UserRepository userRepository,
                         AiReportService aiReportService, CauseTraceService causeTraceService, OrderRequestParser orderParser,
                         PlanningService planningService, LineCommandPublisher lineCommands, ObjectMapper objectMapper,
                         com.gadong.sfaas.monitoring.service.MonitoringService monitoringService) {
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
        long remainingSec = Math.max(0, Duration.between(now, day.plusDays(1).atStartOfDay()).getSeconds());
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
                0L, stoppedKey, "TARGET", "생산 목표 변경", reason, Kst.of(now), stoppedPlan, stoppedDone, 0, "24:00",
                rows.stream().map(r -> new AiReportRequest.LineCondition(r.target(), CANDIDATE, r.capacity(), r.remainingPlan(), null)).toList(),
                "오늘 공장 생산 목표가 " + plantTarget + "개로 바뀜. 목표는 라인마다 똑같이 나눠 저장되는데 "
                        + (stopped.isEmpty() ? "계획정지 라인이 없어 모든 라인이 자기 몫을 만듦."
                        : stoppedKey + "라인은 계획정지 중이라 그 남은 계획을 가동 중인 라인(lineConditions)이 남은 능력 비율대로 나눠 맡는 안.")
                        + " 기준 시각 " + now.toLocalTime().withNano(0) + ", 자정까지 " + remainingSec / 60 + "분, CT " + ct.stripTrailingZeros().toPlainString()
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
        LocalDateTime midnight = day.plusDays(1).atStartOfDay();
        long remainingSec = Math.max(0, Duration.between(base, midnight).getSeconds());
        Map<String, Integer> targets = targets(day);
        Integer plantTarget = targets.isEmpty() ? null : targets.values().stream().mapToInt(Integer::intValue).sum();

        String kind;
        BigDecimal ct;
        int stoppedPlan, stoppedDone;
        List<Row> rows = new ArrayList<>();
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
            for (String line : jdbc.queryForList("SELECT line_id FROM line WHERE line_id <> :line ORDER BY line_id",
                    Map.of("line", f.lineId()), String.class)) {
                if (today && downNow(null, line)) continue;
                int remainingPlan = Math.max(0, targets.getOrDefault(line, 0) - done(day, base, line, null));
                rows.add(new Row(line, remainingPlan, capacity(remainingSec, ct, remainingPlan)));
            }
        }

        // 원인 추적: 근거는 지금 모으고(트랜잭션 안), 문장(LLM)은 재배정 문장과 동시에 써서 기다리는 시간을 늘리지 않아요.
        // 원인 추적이 실패해도 보고서는 만들어요 (causeTrace = null)
        CompletableFuture<CauseTrace> trace = traceAsync(f.downtimeId());

        // 부족 수량·나누기·문장은 AiReportService (팀원 report 패키지). 목표 = 멈춘 쪽 계획, 완료 = 기준 시각까지 실적
        // operatorNote로 계산 규칙을 LLM에 알려 줘요 (문장이 계산과 어긋나지 않게)
        AiReportResponse ai = aiReportService.generate(new AiReportRequest(
                f.downtimeId(), stoppedKey, f.equipmentId(), f.equipmentName(),
                f.codeName() + (f.detail() == null || f.detail().isBlank() ? "" : " (" + f.detail() + ")"),
                Kst.of(f.start()), stoppedPlan, stoppedDone, 0, "24:00",
                rows.stream().map(r -> new AiReportRequest.LineCondition(r.target(), CANDIDATE, r.capacity(), r.remainingPlan(), null)).toList(),
                note(kind, f, base, remainingSec, ct), request));
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

        User user = currentUser();
        ReassignmentReport saved = reportRepository.save(new ReassignmentReport(f.downtimeId(), shortage, now,
                user == null ? null : user.getUserId(), kind, base, ct, stoppedPlan, stoppedDone, plantTarget,
                Math.max(0, shortage - assigned), json(new AiContent(title, ai.summary(), ai.actions(), ai.risks(), markdown,
                        ai.model(), ai.mock(), ai.fallbackReason(), request, ai.requestInterpretation(), trace.join(), null))));
        for (Row r : rows) {
            detailRepository.save(new ReassignmentDetail(saved.getReportId(), r.target(), r.capacity(), add.getOrDefault(r.target(), 0),
                    r.remainingPlan()));
        }
        detailRepository.flush();
        return toResponse(saved);
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

        Integer addQty = body != null && body.addQty() != null ? body.addQty() : parsed.addQty();
        if (addQty == null || addQty <= 0 || addQty > OrderRequestParser.MAX_QTY) {
            throw invalid("추가 주문 수량을 알 수 없습니다. 요청에 수량을 적어 주세요 (예: 추가 주문 300개)");
        }
        String dueAt = body != null && body.dueAt() != null && !body.dueAt().isBlank() ? body.dueAt().trim()
                : parsed.dueAt() != null ? parsed.dueAt() : "24:00";
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
        List<String> saidRunning = body != null && body.runningLines() != null && !body.runningLines().isEmpty()
                ? body.runningLines() : parsed.runningLines();
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

        Map<String, Row> rows = new LinkedHashMap<>();
        for (String l : running) {
            int remainingPlan = Math.max(0, targets.getOrDefault(l, 0) - done(day, now, l, null));
            rows.put(l, new Row(l, remainingPlan, capacity(remainingSec, ct, remainingPlan)));
        }
        int currentCap = rows.values().stream().mapToInt(Row::capacity).sum();
        long newSec = Math.max(0, remainingSec - setup * 60L);
        boolean recommended = false;
        if (newLines.isEmpty()) {
            // 라인을 정하지 않음: "N라인 더"면 그 수만큼, 아니면 모자랄 때만 쉬는 라인을 A부터 더해요
            int want = parsed.addLineCount() != null ? parsed.addLineCount() : 0;
            int cap = currentCap;
            for (String l : idle) {
                if (want > 0 ? newLines.size() >= want : cap >= addQty) break;
                newLines.add(l);
                cap += capacity(newSec, ct, 0);
            }
            recommended = !newLines.isEmpty();
        }
        for (String l : newLines) rows.put(l, new Row(l, 0, capacity(newSec, ct, 0)));
        int withCap = rows.values().stream().mapToInt(Row::capacity).sum();
        PlanningDtos.ReassignmentDetailResponse.Scenario current =
                new PlanningDtos.ReassignmentDetailResponse.Scenario(List.copyOf(running), currentCap, Math.max(0, addQty - currentCap));
        PlanningDtos.ReassignmentDetailResponse.Scenario withNew = new PlanningDtos.ReassignmentDetailResponse.Scenario(
                rows.keySet().stream().sorted().toList(), withCap, Math.max(0, addQty - withCap));
        if (rows.isEmpty()) throw invalid("나눌 수 있는 라인이 없습니다 (모두 고장·제외)");

        String note = "추가 주문 " + addQty + "개, 납기 오늘 " + dueAt + ". 기준 시각 " + now.toLocalTime().withNano(0) + ", 납기까지 "
                + remainingSec / 60 + "분, 라인 CT " + ct.stripTrailingZeros().toPlainString() + "초. 가동 중 라인 " + String.join(",", running)
                + (newLines.isEmpty() ? "" : ", 새로 가동 " + String.join(",", newLines) + "(준비 " + setup + "분 뒤 생산)")
                + ". availableCapacity = 남은 능력, currentPlan = 남은 계획. 지금 라인만이면 남은 능력 합 " + currentCap + "개, 새 라인까지 "
                + withCap + "개. 수량은 allocation 값을 그대로 쓰고, 계획은 제안만 하며 자동으로 바꾸지 않음.";
        AiReportResponse ai = aiReportService.generate(new AiReportRequest(
                0L, "ORDER", "ORDER", "추가 주문", "추가 주문 " + addQty + "개 (납기 오늘 " + dueAt + ")", Kst.of(now),
                addQty, 0, 0, dueAt,
                rows.values().stream().map(r -> new AiReportRequest.LineCondition(r.target(), CANDIDATE, r.capacity(), r.remainingPlan(),
                        newLines.contains(r.target()) ? "새로 가동 (준비 " + setup + "분)" : null)).toList(),
                note, null));
        Map<String, Integer> add = new HashMap<>();
        ai.allocations().forEach(a -> add.put(a.lineId(), a.quantity()));
        int assigned = add.values().stream().mapToInt(Integer::intValue).sum();
        int unmet = Math.max(0, addQty - assigned);

        String title = "추가 주문 " + addQty + "개 배정 검토";
        String summary = ai.summary();
        List<String> actions = ai.actions();
        List<String> risks = ai.risks();
        String markdown = ai.markdown();
        if (ai.mock()) {
            // 템플릿 문장은 고장 보고서용이라 추가 주문에 맞게 다시 써요
            summary = "추가 주문 " + addQty + "개를 오늘 " + dueAt + "까지 만들기 위해 "
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
        OrderInfo order = new OrderInfo(text.isEmpty() ? null : text, addQty, dueAt, setup, List.copyOf(running), List.copyOf(newLines), recommended,
                idle.stream().filter(l -> !newLines.contains(l)).toList(), List.copyOf(excluded), parsed.parsedBy(), List.copyOf(notes), current, withNew);
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
        detailRepository.flush();
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
            if (d.getAddedQty() <= 0) continue;
            int before = targets.getOrDefault(d.getLineId(), 0);
            planningService.setPlan(today, new PlanRequest(d.getLineId(), before + d.getAddedQty()));
            changes.add(new ApplyResponse.TargetChange(d.getLineId(), before, before + d.getAddedQty()));
        }

        // 3) 승인 표시
        User user = currentUser();
        r.approve(user == null ? null : user.getUserId(), now);

        // 4) 가동 명령
        boolean sent = true;
        for (String line : order.newLines()) sent &= lineCommands.send(line, "start");
        if (!order.newLines().isEmpty() && !sent) notes.add("라인 가동 명령을 MQTT 브로커에 보내지 못했습니다. 재생기·설비에서 직접 가동해 주세요 (예: start D)");
        if (order.newLines().isEmpty()) notes.add("새로 켤 라인이 없어 목표만 바꿨습니다");
        return new ApplyResponse(reportId, Kst.of(now), order.newLines(), ended, changes, !order.newLines().isEmpty() && sent, notes);
    }

    /** LLM에 주는 계산 규칙 설명 (보고서 문장의 근거) */
    private static String note(String kind, Fault f, LocalDateTime base, long remainingSec, BigDecimal ct) {
        String common = " 기준 시각 " + base.toLocalTime() + ", 자정까지 남은 시간 " + remainingSec / 60 + "분, CT " + ct.stripTrailingZeros().toPlainString()
                + "초. 실적은 기준 시각까지 출고 양품 수. lineConditions의 availableCapacity = 남은 시간 ÷ CT − 남은 계획(currentPlan). "
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
        if (!Boolean.TRUE.equals(r.getApproved())) apply(r, user);
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
            }
            return;
        }
        if (!"line".equals(kind) && !"target".equals(kind)) return;
        int moved = 0;
        for (ReassignmentDetail d : rows) {
            if (d.getAddedQty() <= 0) continue;
            addTarget(day, d.getLineId(), d.getAddedQty(), user);
            moved += d.getAddedQty();
        }
        // 멈춘 라인 목표에서 옮긴 만큼 빼요 (고장 라인 1개 또는 계획정지 라인들, 실적 아래로는 안 내림)
        List<String> from = "line".equals(kind) ? List.of(fault(r.getDowntimeId()).lineId()) : stoppedLinesOf(r, rows);
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
                : Math.max(0, Duration.between(r.getBaseAt(), r.getBaseAt().toLocalDate().plusDays(1).atStartOfDay()).getSeconds());
        String createdBy = r.getCreatedBy() == null ? null
                : userRepository.findById(r.getCreatedBy()).map(User::getUserName).orElse(null);
        String documentNo = documentNo(r);
        return new ReassignmentDetailResponse(r.getReportId(), r.getDowntimeId(), f.lineId(), r.getLossQty(), details, documentNo,
                f.equipmentId(), f.code(), f.detail(), Kst.of(f.start()), Kst.of(r.getCreatedAt()), createdBy, r.getKind(),
                Kst.of(r.getBaseAt()), remainingSec, r.getCtSec() == null ? null : r.getCtSec().doubleValue(),
                r.getStoppedPlan(), r.getStoppedDone(), r.getTargetQty(), r.getUnmetQty(), ai(r.getAiContent()),
                Boolean.TRUE.equals(r.getApproved()),
                r.getApprovedBy() == null ? null : userRepository.findById(r.getApprovedBy()).map(User::getUserName).orElse(null),
                Kst.of(r.getApprovedAt()));
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
