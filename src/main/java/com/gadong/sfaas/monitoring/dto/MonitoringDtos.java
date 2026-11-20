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
    /** updatedAt·updatedBy = 마지막으로 바꾼 시각·사람 이름 (11/20 추가, 바꾼 적 없으면 사람은 null) */
    public record ThresholdResponse(int id, String equipmentId, String process, String metric, Double low, Double high,
                                    OffsetDateTime updatedAt, String updatedBy) {}

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

    /** E-01. productId는 Node-RED가 안 넣었으면 null */
    public record EquipmentDataResponse(OffsetDateTime ts, String equipmentId, String productId, String process, String result,
                                        Map<String, Object> values) {}

    /** M-15 설비별 사이클 수·평균 CT(ms). 행이 1개 이하면 avgCtMs는 null */
    /** ng = 그날 이 설비에서 공정 판정 NG(폐기)된 수 (11/20, 라인 흐름 설비 칸). 프레스는 NG가 난 단 */
    public record CycleResponse(String equipmentId, long cycles, Long avgCtMs, long ng) {}

    /** M-16 코일 입고 현황. 1롤 = 제품 20개. 재고는 그날 끝(오늘이면 지금)까지 입고 합 − 슬리팅에 들어간 수 */
    public record CoilTodayResponse(String date,
                                    @Schema(description = "그날 입고한 롤 수 (coil_receipt 행 수)") int receivedRolls,
                                    @Schema(description = "그날 입고한 제품 수 (product_qty 합)") int receivedQty,
                                    @Schema(description = "슬리팅 앞 코일 재고, 제품 수 (M-13 COIL 재공과 같음)") int stockQty,
                                    @Schema(description = "코일 재고, 롤 (stockQty ÷ 20, 소수 1자리. 예: 74개 → 3.7)") double stockRolls,
                                    @Schema(description = "그날 슬리팅에 들어간 제품 수") int inputQty,
                                    List<CoilReceiptItem> receipts) {
        public record CoilReceiptItem(String materialId, OffsetDateTime receivedAt, int productQty) {}
    }

    /** M-17 공장 안 제품 위치 (3D 물류). 입고 뒤·출고 전, 공정 불량(폐기)은 빼요 */
    public record FlowPositionsResponse(OffsetDateTime at, List<ProductPosition> products) {
        /** equipmentId(설비 안 처리 중)와 pile(공정 사이 재공) 중 하나만 값이 있어요 */
        public record ProductPosition(String productId, String lineId,
                                      @Schema(description = "처리 중인 설비. 기다리는 중이면 null") String equipmentId,
                                      @Schema(description = "SL-PR · PR-WD · WD-PT (라인 안) · PT1:PT-VI · PT2:PT-VI. 처리 중이면 null") String pile) {}
    }
}
