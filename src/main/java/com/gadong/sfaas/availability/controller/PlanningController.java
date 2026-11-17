package com.gadong.sfaas.availability.controller;

import com.gadong.sfaas.availability.dto.PlanningDtos.*;
import com.gadong.sfaas.availability.service.PlanningService;
import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.monitoring.service.ProductTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

    public PlanningController(PlanningService service, ProductTraceService productTrace) {
        this.service = service;
        this.productTrace = productTrace;
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
    @PutMapping("/production/plans/{date}")
    public PlanResponse setPlan(@PathVariable @DateTimeFormat(pattern = DATE) LocalDate date,
                                @RequestBody PlanRequest request) {
        return service.setPlan(date, request);
    }

    @Operation(summary = "R-01 재배정 보고서 목록", description = "P1. reassignment_report 조회, createdAt 최신순. 보고서는 Spring Boot가 자동으로 만들고 승인은 없어요.")
    @GetMapping("/reassignments")
    public PageResponse<ReassignmentReportResponse> reports(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                            @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "50") int size) {
        return service.reports(from, to, page, size);
    }

    @Operation(summary = "R-02 재배정 보고서 상세 (라인별 추가 배정)", description = "P1. reassignment_detail 조회")
    @GetMapping("/reassignments/{reportId}")
    public ReassignmentDetailResponse report(@PathVariable long reportId) {
        return service.report(reportId);
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
