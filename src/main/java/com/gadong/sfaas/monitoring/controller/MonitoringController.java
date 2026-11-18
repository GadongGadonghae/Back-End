package com.gadong.sfaas.monitoring.controller;

import com.gadong.sfaas.monitoring.dto.MonitoringDtos.*;
import com.gadong.sfaas.monitoring.service.MonitoringService;
import com.gadong.sfaas.monitoring.service.TodayStats;
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
@RequestMapping("/api")
public class MonitoringController {

    private final MonitoringService service;
    private final TodayStats todayStats;

    public MonitoringController(MonitoringService service, TodayStats todayStats) {
        this.service = service;
        this.todayStats = todayStats;
    }

    @Operation(summary = "M-15 설비별 사이클 수·평균 CT (공정 상세)",
            description = "그날 설비별 처리 수(나온 제품)와 CT(ms, 제품이 들어간 시각 간격의 중앙값이라 정지 시간은 빠져요, product_flow). "
                    + "date 생략 시 오늘. 프레스는 PR1~PR4 설비마다 나와요. 시각이 2개보다 적으면 CT는 null")
    @GetMapping("/monitoring/processes/{process}/cycles")
    public List<CycleResponse> cycles(@PathVariable String process,
                                      @RequestParam(required = false) String lineId,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return todayStats.cycles(process, lineId, date);
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
