package com.gadong.sfaas.availability.controller;

import com.gadong.sfaas.availability.dto.AvailabilityDtos.*;
import com.gadong.sfaas.availability.service.AvailabilityService;
import com.gadong.sfaas.global.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "가동률")
@RestController
@RequestMapping("/api/v1")
public class AvailabilityController {

    private static final String DATE = "yyyy-MM-dd";

    private final AvailabilityService service;

    public AvailabilityController(AvailabilityService service) {
        this.service = service;
    }

    @Operation(summary = "U-01 공장 전체 가동률 요약 (목표/실적)",
            description = "P1. OEE = availability × performance × quality. 시간당 생산 능력 미정이라 performance·oee·lossQty 는 null 이에요.")
    @GetMapping("/utilization/summary")
    public SummaryResponse summary(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                   @RequestParam(required = false) String lineId) {
        return service.summary(from, to, lineId);
    }

    @Operation(summary = "U-02 가동률 추이", description = "P1")
    @GetMapping("/utilization/trend")
    public List<TrendResponse> trend(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                     @RequestParam(defaultValue = "day") String unit,
                                     @RequestParam(required = false) String lineId) {
        return service.trend(from, to, unit, lineId);
    }

    @Operation(summary = "U-03 공정별 가동률", description = "P1")
    @GetMapping("/utilization/by-process")
    public List<ByProcessResponse> byProcess(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                             @RequestParam(required = false) String lineId) {
        return service.byProcess(from, to, lineId);
    }

    @Operation(summary = "U-04 라인별 가동률", description = "P1")
    @GetMapping("/utilization/by-line")
    public List<ByLineResponse> byLine(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to) {
        return service.byLine(from, to);
    }

    @Operation(summary = "U-05 설비별 가동률 순위 (낮은 순)", description = "P2")
    @GetMapping("/utilization/by-equipment")
    public List<ByEquipmentResponse> byEquipment(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                                 @RequestParam(required = false) String process,
                                                 @RequestParam(defaultValue = "10") int limit) {
        return service.byEquipment(from, to, process, limit);
    }

    @Operation(summary = "U-06 요일별 가동률 + 비가동 건수", description = "P2")
    @GetMapping("/utilization/by-weekday")
    public List<ByWeekdayResponse> byWeekday(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                             @RequestParam(required = false) String lineId) {
        return service.byWeekday(from, to, lineId);
    }

    @Operation(summary = "U-07 비가동 사유별 집계 (파이·표)", description = "P1")
    @GetMapping("/downtime/summary")
    public DowntimeSummaryResponse downtimeSummary(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                   @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                                   @RequestParam(defaultValue = "line") String groupBy) {
        return service.downtimeSummary(from, to, groupBy);
    }

    @Operation(summary = "U-08 알람·비가동 이력 목록 (조회 조건 + 조치 담당자)", description = "P1. minMinutes=1 이면 1분 이상 정지만 1건으로 세요. 조치 담당자는 번호(actionUserId)로 거르고, 응답에 이름(actionUserName)을 붙여요.")
    @GetMapping("/downtime/events")
    public PageResponse<DowntimeEventResponse> downtimeEvents(
            @RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
            @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
            @RequestParam(required = false) String lineId,
            @RequestParam(required = false) String process,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) Boolean inProgress,
            @RequestParam(required = false) Long actionUserId,
            @RequestParam(required = false) String equipmentId,
            @RequestParam(defaultValue = "1") int minMinutes,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.downtimeEvents(from, to, lineId, process, status, reasonCode, inProgress, actionUserId,
                equipmentId, minMinutes, page, size);
    }

    @Operation(summary = "U-09 비가동 사유·조치 담당자·메모 입력/수정", description = "P2. 자동 판정이 틀린 건만 사람이 고쳐요. 조치 담당자는 이름이 아니라 번호(actionUserId)로 받아요.")
    @PatchMapping("/downtime/events/{eventId}")
    public DowntimeEventUpdateResponse updateDowntimeEvent(@PathVariable long eventId,
                                                           @RequestBody DowntimeEventUpdateRequest request) {
        return service.updateDowntimeEvent(eventId, request);
    }

    @Operation(summary = "U-10 장비 고장 Top N (30분 이상)", description = "P2")
    @GetMapping("/downtime/top")
    public List<DowntimeTopResponse> downtimeTop(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                                 @RequestParam(defaultValue = "30") int minMinutes,
                                                 @RequestParam(defaultValue = "5") int limit) {
        return service.downtimeTop(from, to, minMinutes, limit);
    }

    @Operation(summary = "U-11 양품률 추이", description = "P1")
    @GetMapping("/production/yield")
    public List<YieldResponse> yield(@RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to,
                                     @RequestParam(defaultValue = "day") String unit,
                                     @RequestParam(required = false) String groupBy) {
        return service.yield(from, to, unit, groupBy);
    }

    @Operation(summary = "U-12 설비 R/S/I/D 상태 타임라인", description = "P2")
    @GetMapping("/equipments/{equipmentId}/status-history")
    public List<StatusSegmentResponse> statusHistory(@PathVariable String equipmentId,
                                                     @RequestParam @DateTimeFormat(pattern = DATE) LocalDate from,
                                                     @RequestParam @DateTimeFormat(pattern = DATE) LocalDate to) {
        return service.statusHistory(equipmentId, from, to);
    }
}
