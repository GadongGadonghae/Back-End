package com.gadong.sfaas.availability.controller;

import com.gadong.sfaas.availability.dto.PlanningDtos.*;
import com.gadong.sfaas.availability.service.PlanningService;
import com.gadong.sfaas.availability.service.ReplanService;
import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.monitoring.service.ProductTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "생산 계획 · 재배정 · 계획정지")
@RestController
@RequestMapping("/api")
public class PlanningController {

    private static final String DATE = "yyyy-MM-dd";

    private final PlanningService service;
    private final ProductTraceService productTrace;
    private final ReplanService replanService;
    private final com.gadong.sfaas.report.flow.ReportFlowService reportFlow;

    public PlanningController(PlanningService service, ProductTraceService productTrace, ReplanService replanService,
                              com.gadong.sfaas.report.flow.ReportFlowService reportFlow) {
        this.service = service;
        this.productTrace = productTrace;
        this.replanService = replanService;
        this.reportFlow = reportFlow;   // 계획수정 보고서를 만들고 승인하면 결재·전송 흐름(R-10~)에도 기록 (v0.3.15)
    }

    @Operation(summary = "G-01 생산량 조회 (기간별·라인별·계획 대비)",
            description = "P2. 실적은 생산 수(검사 수 + 공정 불량 수)예요. "
                    + "unit=hour는 응답 모양이 달라요: [{ period, lineId, inspected, good, processNg, cumulativeGood }] "
                    + "(inspected_at 기준 1시간 구간, 빈 시간은 0, 오늘은 지금 시간까지, cumulativeGood은 from부터 라인별 누적)")
    @GetMapping("/production/summary")
    public List<?> productionSummary(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                     @RequestParam(defaultValue = "day") String unit,
                                     @RequestParam(required = false) String lineId) {
        if ("hour".equals(unit)) return productTrace.hourly(from, to, lineId);
        return service.productionSummary(from, to, unit, lineId);
    }

    @Operation(summary = "G-02 일 생산 계획(목표) 조회", description = "P2. production_target 테이블")
    @GetMapping("/production/plans")
    public List<PlanResponse> plans(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                    @RequestParam(required = false) String lineId) {
        return service.plans(from, to, lineId);
    }

    @Operation(summary = "G-03 일 생산 계획(목표) 설정", description = "P2")
    @PreAuthorize("hasRole('PLANT_HEAD')") // 생산 목표 설정 = 공장장만 (11/20)
    @PutMapping("/production/plans/{date}")
    public PlanResponse setPlan(@PathVariable @DateTimeFormat(pattern = DATE) LocalDate date,
                                @RequestBody PlanRequest request) {
        return service.setPlan(date, request);
    }

    @Operation(summary = "R-01 재배정 보고서 목록", description = "P1. reassignment_report 조회, createdAt 최신순. code = 고장 사유 코드. 보고서는 R-03으로 만들고(생산관리팀장), R-04로 공장장이 승인(결재)해요. approved = 승인 여부.")
    @GetMapping("/reassignments")
    public PageResponse<ReassignmentReportResponse> reports(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                            @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "50") int size) {
        return service.reports(from, to, page, size);
    }

    @Operation(summary = "R-02 재배정(계획수정) 보고서 상세",
            description = "P1. 라인별 추가 배정(details) + 계산 기준(kind·baseAt·remainingSec·ctSec·stoppedPlan·stoppedDone·targetQty·unmetQty) + AI 문장(ai). "
                    + "kind = line(라인 전체 정지로 봄) · paint(다른 도장 1대가 맡음) · vision(대체 없음). 도장 보고서의 targetLineId는 설비 ID(PT1·PT2)예요.")
    @GetMapping("/reassignments/{reportId}")
    public ReassignmentDetailResponse report(@PathVariable long reportId) {
        return replanService.report(reportId);
    }

    @Operation(summary = "R-03 AI 계획수정 보고서 만들기 (고장 건으로)",
            description = "고장(D) downtimeId로 부족 수량·다른 라인(도장) 추가 배정·AI 문장을 계산해 저장하고 R-02 모양으로 줘요. "
                    + "오늘 고장이면 지금 기준, 지난 날이면 고장 시작 기준. 누를 때마다 새 보고서예요. "
                    + "additionalRequest(선택, 500자): \"A라인은 제외\", \"F라인 정지\", \"납기 우선\" 등 → 제외·정지 라인은 배정에서 빠지고 해석 결과는 ai.requestInterpretation. "
                    + "AI 문장은 키가 없거나 실패하면 템플릿(ai.mock=true, ai.fallbackReason). 계획은 제안만 하고 바꾸지 않아요.")
    @PreAuthorize("hasRole('PROD_LEAD')") // AI 리포트 작성 = 생산관리팀장만 (11/20)
    @PostMapping("/reassignments")
    @ResponseStatus(HttpStatus.CREATED)
    public ReassignmentDetailResponse createReport(@RequestBody ReassignmentCreateRequest request) {
        ReassignmentDetailResponse r = replanService.create(request == null ? null : request.downtimeId(), request == null ? null : request.additionalRequest(),
                request == null ? null : request.basis());
        reportFlow.syncReplan(r);
        return r;
    }

    @Operation(summary = "R-04 AI 계획수정 보고서 승인(결재)",
            description = "11/20. 공장장만. 보고서에 승인 여부·승인자·시각을 남기고 R-02 모양으로 줘요. 생산계획을 실제로 바꾸지는 않아요. "
                    + "추가 주문 배정(kind = order)은 R-06 승인·적용으로 승인해요.")
    @PreAuthorize("hasRole('PLANT_HEAD')")
    @PostMapping("/reassignments/{reportId}/approve")
    public ReassignmentDetailResponse approveReport(@PathVariable long reportId) {
        ReassignmentDetailResponse r = replanService.approve(reportId);
        reportFlow.syncApproved(r);
        return r;
    }

    @Operation(summary = "R-05 추가 주문 배정 검토 만들기",
            description = "고장 건 없이, 추가 주문을 지금 가동 중인 라인과 새로 켤 라인에 나누는 검토 보고서를 만들어 저장하고 R-02 모양으로 줘요 (kind = order). "
                    + "request(500자): \"지금 A~D 4개 라인만 돌리는데 추가 주문 300개 들어왔어. 오늘 안에 끝내야 하니 한 라인 더 가동해줘\" → AI가 조건으로 해석 "
                    + "(키가 없거나 실패하면 규칙 해석, 말에 없는 라인은 버림). addQty·dueAt(HH:mm)·runningLines·newLines·setupMinutes를 주면 해석보다 우선. "
                    + "결과의 ai.order에 해석 조건과 '지금 라인만 / 새 라인까지' 비교가 있어요. 만들기만 하고 계획은 R-06 승인·적용 때 바꿔요.")
    @PreAuthorize("hasRole('PROD_LEAD')") // AI 리포트 작성 = 생산관리팀장
    @PostMapping("/reassignments/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ReassignmentDetailResponse createOrderReport(@RequestBody OrderCreateRequest request) {
        ReassignmentDetailResponse r = replanService.createOrder(request);
        reportFlow.syncReplan(r);
        return r;
    }

    @Operation(summary = "R-06 추가 주문 배정 승인·적용",
            description = "공장장만. kind = order 보고서만, 한 번만. 새 라인의 계획정지 종료 → 오늘 라인 목표 += 배정 수량 → 승인 표시(R-04와 같은 칸) → "
                    + "새 라인에 MQTT sfaas/ctl/line {lineId, action: start} (재생기 --hold 라인이 받아 생산 시작 → 대시보드에 가동으로 보여요).")
    @PreAuthorize("hasRole('PLANT_HEAD')") // 생산 목표를 바꾸므로 공장장 (G-03과 같음)
    @PostMapping("/reassignments/{reportId}/apply")
    public ApplyResponse applyReport(@PathVariable long reportId) {
        ApplyResponse applied = replanService.apply(reportId);
        reportFlow.syncApproved(replanService.report(reportId));   // 결재 흐름도 결재 완료로 → 보고서 전송 가능
        return applied;
    }

    @Operation(summary = "S-01 계획정지 조회 (진행 중 + 이력)",
            description = "P2. 라인 정지 1건은 stopId 하나로 묶고 그 라인의 설비 6대를 equipmentIds 로 줘요.")
    @GetMapping("/planned-stops")
    public List<PlannedStopResponse> plannedStops(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                                  @RequestParam(required = false) String lineId,
                                                  @RequestParam(required = false) Boolean inProgress) {
        return service.plannedStops(from, to, lineId, inProgress);
    }

    @Operation(summary = "S-02 계획정지 시작 (원하는 시점에)",
            description = "P2. 라인 설비 6대에 같은 stopId 로 기록해요. startAt 을 생략하면 지금이고, 라인에 진행 중인 계획정지가 있으면 409예요.")
    @PostMapping("/planned-stops")
    @ResponseStatus(HttpStatus.CREATED)
    public PlannedStopStartedResponse startPlannedStop(@RequestBody PlannedStopRequest request) {
        return service.startPlannedStop(request);
    }

    @Operation(summary = "S-03 계획정지 종료", description = "P2. endAt 을 생략하면 지금까지예요.")
    @PatchMapping("/planned-stops/{stopId}/end")
    public PlannedStopEndResponse endPlannedStop(@PathVariable long stopId,
                                                 @RequestBody(required = false) PlannedStopEndRequest request) {
        return service.endPlannedStop(stopId, request);
    }
}
