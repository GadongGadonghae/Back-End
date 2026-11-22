package com.gadong.sfaas.monitoring.controller;

import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.*;
import com.gadong.sfaas.monitoring.service.MonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "모니터링 · 알람")
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final MonitoringService service;

    public AlarmController(MonitoringService service) {
        this.service = service;
    }

    @Operation(summary = "M-04 현재 활성 알람", description = "P1. alarm 테이블 조회")
    @GetMapping("/active")
    public List<AlarmResponse> active(@RequestParam(required = false) String lineId) {
        return service.activeAlarms(lineId);
    }

    @Operation(summary = "M-05 알람 이력", description = "P1. occurredAt 최신순")
    @GetMapping
    public PageResponse<AlarmHistoryResponse> alarms(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) String equipmentId,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.alarms(from, to, equipmentId, page, size);
    }

    @Operation(summary = "M-06 알람 확인 처리", description = "P2. 본문 ackUserId 는 확인한 사용자 번호예요. 이미 확인한 알람은 처음 기록을 유지해요.")
    @PatchMapping("/{alarmId}/ack")
    public AckResponse ack(@PathVariable long alarmId, @RequestBody(required = false) AckRequest request) {
        return service.ack(alarmId, request);
    }

    @Operation(summary = "M-10 알람 기준치 조회", description = "P1. threshold 테이블(설비별 기준치). process, equipmentId 로 좁혀요.")
    @GetMapping("/thresholds")
    public List<ThresholdResponse> thresholds(@RequestParam(required = false) String process,
                                              @RequestParam(required = false) String equipmentId) {
        return service.thresholds(process, equipmentId);
    }

    @Operation(summary = "M-11 알람 기준치 수정", description = "P1")
    @PreAuthorize("hasRole('PROD_LEAD')") // 관리값 기준 설정 = 생산관리 팀장만 (11/20)
    @PutMapping("/thresholds/{id}")
    public ThresholdUpdateResponse updateThreshold(@PathVariable int id, @RequestBody ThresholdUpdateRequest request) {
        return service.updateThreshold(id, request);
    }
}
