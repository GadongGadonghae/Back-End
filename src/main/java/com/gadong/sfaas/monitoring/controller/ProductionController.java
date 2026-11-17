package com.gadong.sfaas.monitoring.controller;

import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.*;
import com.gadong.sfaas.monitoring.service.MonitoringService;
import com.gadong.sfaas.monitoring.service.TodayStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "모니터링 · 생산·검사")
@RestController
@RequestMapping("/api")
public class ProductionController {

    private final MonitoringService service;
    private final TodayStats todayStats;

    public ProductionController(MonitoringService service, TodayStats todayStats) {
        this.service = service;
        this.todayStats = todayStats;
    }

    @Operation(summary = "M-14 시간대별 출고 양품 (대시보드·라인 상세 그래프)",
            description = "done = 그 시간의 검사 OK(M-07 ok 기준). date 생략 시 오늘이고 지금 시각까지만 줘요. "
                    + "planCumulative = 그날 계획(G-03) × (hour+1) ÷ 24, 계획이 없으면 null")
    @GetMapping("/production/hourly")
    public HourlyResponse hourly(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 @RequestParam(required = false) String lineId) {
        return todayStats.hourly(date, lineId);
    }

    @Operation(summary = "M-07 출고 양품/불량 카운트",
            description = "P1. total = inspected + processNg, ok = inspected − ngVision, yieldRate = ok ÷ total. "
                    + "byProcess 는 lineId 를 지정했을 때만 채워요. groupBy=paint 는 도장 앞에서 폐기된 불량이 빠져요.")
    @GetMapping("/production/counts")
    public ProductionCountsResponse counts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                           @RequestParam(required = false) String lineId,
                                           @RequestParam(defaultValue = "line") String groupBy) {
        return service.counts(date, lineId, groupBy);
    }

    @Operation(summary = "M-08 비전 검사 결과 목록", description = "P1. result 는 OK/NG")
    @GetMapping("/inspections")
    public PageResponse<InspectionResponse> inspections(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                        @RequestParam(required = false) String lineId,
                                                        @RequestParam(required = false) String paintId,
                                                        @RequestParam(required = false) String result,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "50") int size) {
        return service.inspections(from, to, lineId, paintId, result, page, size);
    }

    @Operation(summary = "M-09 도장 경로 전환", description = "P1. line.current_paint_id 변경 + paint_route_history 기록. Q-03 MQTT 명령은 연동 전이에요")
    @PutMapping("/lines/{lineId}/paint-route")
    public PaintRouteResponse changePaintRoute(@PathVariable String lineId, @RequestBody PaintRouteRequest request) {
        return service.changePaintRoute(lineId, request);
    }

    @Operation(summary = "M-13 공정별 재공품(WIP) 수량",
            description = "P1. 앞 공정 OK 행 수 − 다음 공정 행 수(누적). 코일입고는 coil_receipt.product_qty 합 − 슬리팅 행 수이고 lineId 를 주면 빠져요.")
    @GetMapping("/production/wip")
    public List<WipResponse> wip(@RequestParam(required = false) String lineId,
                                 @Parameter(description = "도장 설비(PT1·PT2). 주면 그 도장의 앞(용접→도장)·뒤(도장→검사) 재공만 줘요")
                                 @RequestParam(required = false) String paintId) {
        return service.wip(lineId, paintId);
    }

    @Operation(summary = "E-01 설비 데이터 조회 (공정별 테이블의 사이클 단위 행)",
            description = "P2. values 키는 DB 컬럼명의 camelCase예요. 최신순")
    @GetMapping("/equipment-data")
    public PageResponse<EquipmentDataResponse> equipmentData(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String lineId,
            @RequestParam(required = false) String process,
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.equipmentData(from, to, lineId, process, equipmentId, result, page, size);
    }
}
