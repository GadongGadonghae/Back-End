package com.gadong.sfaas.monitoring.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 모니터링 화면 API 응답·요청 (API 명세서 v3: M-01 ~ M-13, E-01) */
public final class MonitoringDtos {
    private MonitoringDtos() {}

    /** M-01 · M-12 · W-01 공통 설비 상태 필드 */
    public record EquipmentStatusResponse(String equipmentId, String lineId, String process,
                                          @Schema(description = "R 가동 / S 정지(계획) / I 대기 / D 고장·알람 / DC 통신 끊김") String status,
                                          OffsetDateTime since,
                                          @Schema(description = "R 이면 null") String reasonCode,
                                          Map<String, Object> lastValues) {}

    /** M-02 */
    public record LineSummaryResponse(String lineId,
                                      @JsonProperty("R") int r, @JsonProperty("S") int s,
                                      @JsonProperty("I") int i, @JsonProperty("D") int d,
                                      @JsonProperty("DC") int dc, String worstStatus) {}

    /** M-03 */
    public record MetricsResponse(String equipmentId, List<Series> metrics) {
        public record Series(String metric, String unit, Double limitLow, Double limitHigh, List<Point> points) {}
        public record Point(OffsetDateTime ts, double value, String result) {}
    }

    /** M-04 · W-02. itemName은 벗어난 항목(고장 신호면 null), 값·기준은 알람 순간의 기록이에요 */
    public record AlarmResponse(long alarmId, String equipmentId, String itemName, Double measuredValue, Double lowerLimit,
                                Double upperLimit, String level, String message, OffsetDateTime occurredAt, OffsetDateTime clearedAt) {}

    /** M-05: M-04 필드 + 확인 시각 */
    public record AlarmHistoryResponse(long alarmId, String equipmentId, String level, String message, String itemName,
                                       Double measuredValue, Double lowerLimit, Double upperLimit, OffsetDateTime occurredAt,
                                       OffsetDateTime clearedAt, OffsetDateTime ackedAt) {}

    /** M-06 */
    public record AckRequest(Integer ackUserId) {}

    public record AckResponse(long alarmId, Integer ackUserId, OffsetDateTime ackedAt) {}

    /** M-10 */
    public record ThresholdResponse(int id, String equipmentId, String process, String metric, Double low, Double high) {}

    /** M-11 */
    public record ThresholdUpdateRequest(Double low, Double high) {}

    public record ThresholdUpdateResponse(int id, Double low, Double high, OffsetDateTime updatedAt) {}

    /** M-07: total(생산) = inspected(검사 수) + processNg(공정 불량), ok = inspected − ngVision, yieldRate = ok ÷ total × 100 */
    public record ProductionCountsResponse(String date, int total, int processNg, int inspected, int ngVision, int ok,
                                           double yieldRate, List<GroupCount> byGroup, List<ProcessCount> byProcess) {
        public record GroupCount(String key, int total, int good, int ng) {}
        public record ProcessCount(String process, int produced, int ng) {}
    }

    /** M-08 */
    public record InspectionResponse(long inspectionId, String productId, String lineId, String paintId, String result, OffsetDateTime inspectedAt) {}

    /** M-09 */
    public record PaintRouteRequest(String paintId, String reason) {}

    public record PaintRouteResponse(String lineId, String currentPaintId, OffsetDateTime changedAt) {}

    /** M-13 */
    public record WipResponse(String process, String nextProcess, int wipQty) {}

    /** E-01 */
    public record EquipmentDataResponse(OffsetDateTime ts, String equipmentId, String process, String result,
                                        Map<String, Object> values) {}
}
