package com.gadong.sfaas.availability.controller;

import com.gadong.sfaas.availability.dto.PlanningDtos.*;
import com.gadong.sfaas.availability.service.PlanningService;
import com.gadong.sfaas.global.response.PageResponse;
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
@RequestMapping("/api/v1")
public class PlanningController {

    private static final String DATE = "yyyy-MM-dd";

    private final PlanningService service;

    public PlanningController(PlanningService service) {
        this.service = service;
    }

    @Operation(summary = "G-01 생산량 조회 (기간별·라인별·계획 대비) (stub)",
            description = "P2. 공용 도장 설비 불량을 라인에 나누는 기준이 정해지지 않아 아직 예시 값이에요.")
    @GetMapping("/production/summary")
    public List<ProductionSummaryResponse> productionSummary(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                             @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                                             @RequestParam(defaultValue = "day") String unit,
                                                             @RequestParam(required = false) String lineId) {
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

    @Operation(summary = "R-01 재배정 보고서 목록", description = "P1. reassignment_report 조회, createdAt 최신순. status 는 PENDING/APPROVED. 보고서는 Spring Boot가 자동으로 만들어요.")
    @GetMapping("/reassignments")
    public PageResponse<ReassignmentReportResponse> reports(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                            @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return service.reports(from, to, status, page, size);
    }

    @Operation(summary = "R-02 재배정 보고서 상세 (라인별 추가 배정)", description = "P1. reassignment_detail 조회. approvedBy 는 사용자 번호예요.")
    @GetMapping("/reassignments/{reportId}")
    public ReassignmentDetailResponse report(@PathVariable long reportId) {
        return service.report(reportId);
    }

    @Operation(summary = "R-03 재배정 관리자 승인", description = "P2. approvedBy 는 사용자 번호(users.user_id). 승인 권한 확인은 JWT 연동 후예요.")
    @PatchMapping("/reassignments/{reportId}/approve")
    public ApproveResponse approve(@PathVariable long reportId, @RequestBody ApproveRequest request) {
        return service.approve(reportId, request);
    }

    @Operation(summary = "S-01 계획정지 일정 조회 (stub)", description = "P2. 명세(일정·planId)와 DB(stop_group_id)가 달라 정해질 때까지 예시 값이에요.")
    @GetMapping("/planned-stops")
    public List<PlannedStopResponse> plannedStops(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                                  @RequestParam(required = false) String lineId) {
        return service.plannedStops(from, to, lineId);
    }

    @Operation(summary = "S-02 계획정지 일정 등록 (stub)", description = "P2")
    @PostMapping("/planned-stops")
    @ResponseStatus(HttpStatus.CREATED)
    public PlannedStopCreatedResponse createPlannedStop(@RequestBody PlannedStopRequest request) {
        return service.createPlannedStop(request);
    }
}
