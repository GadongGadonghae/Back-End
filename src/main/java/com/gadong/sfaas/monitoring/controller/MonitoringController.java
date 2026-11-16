package com.gadong.sfaas.monitoring.controller;

import com.gadong.sfaas.monitoring.dto.MonitoringDtos.*;
import com.gadong.sfaas.monitoring.service.MonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "모니터링 · 설비 상태")
@RestController
@RequestMapping("/api/v1")
public class MonitoringController {

    private final MonitoringService service;

    public MonitoringController(MonitoringService service) {
        this.service = service;
    }

    @Operation(summary = "M-01 전체 설비 현재 상태 (흐름도용)", description = "P1. 39건. 설비 상태 공통 필드는 M-12·W-01과 같아요.")
    @GetMapping("/monitoring/status")
    public List<EquipmentStatusResponse> status(@RequestParam(required = false) String lineId) {
        return service.status(lineId);
    }

    @Operation(summary = "M-02 라인별 상태 요약", description = "P1")
    @GetMapping("/monitoring/lines/summary")
    public List<LineSummaryResponse> linesSummary() {
        return service.linesSummary();
    }

    @Operation(summary = "M-03 설비 공정 값 추이 (상·하한선 포함)",
            description = "P1. interval=raw 는 사이클 값 그대로, 5s 는 조회 때 5초 평균을 계산해요(저장하지 않음).")
    @GetMapping("/equipments/{equipmentId}/metrics")
    public MetricsResponse metrics(@PathVariable String equipmentId,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(required = false) String metric,
                                   @RequestParam(defaultValue = "raw") String interval) {
        return service.metrics(equipmentId, from, to, metric, interval);
    }

    @Operation(summary = "M-12 공정 상세: 공정별 설비 목록·상태·원인", description = "P1. lineId 를 생략하면 전체예요.")
    @GetMapping("/monitoring/processes/{process}/equipments")
    public List<EquipmentStatusResponse> processEquipments(@PathVariable String process,
                                                           @RequestParam(required = false) String lineId) {
        return service.processEquipments(process, lineId);
    }
}
