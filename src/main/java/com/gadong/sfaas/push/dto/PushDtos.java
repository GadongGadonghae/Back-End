package com.gadong.sfaas.push.dto;

import com.gadong.sfaas.monitoring.dto.MonitoringDtos.FlowPositionsResponse.ProductPosition;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.MetricsResponse;
import java.time.OffsetDateTime;
import java.util.List;

/** WebSocket 푸시 메시지 (API 명세서 W-02 ~ W-05). W-01은 MonitoringDtos.EquipmentStatusResponse를 그대로 써요. */
public final class PushDtos {
    private PushDtos() {}

    /** W-02 /topic/alarms. type = RAISED / CLEARED / ACKED */
    public record AlarmMessage(long alarmId, String type, String equipmentId, String level, String message,
                               OffsetDateTime occurredAt) {}

    /** W-03 /topic/production. last = 방금 검사한 제품, today = 오늘 누적 (M-07과 같은 계산) */
    public record ProductionMessage(Last last, Today today) {
        public record Last(String lineId, String paintId, String result) {}
        public record Today(int total, int processNg, int inspected, int ngVision, int ok, double yieldRate) {}
    }

    /**
     * W-04 /topic/metrics/{equipmentId} (11/22). 지난번 보낸 뒤 새로 생긴 점만, M-03 metrics 모양 그대로.
     * ts = 이번에 보낸 점 중 가장 늦은 시각 (다음 번은 그 뒤 점부터)
     */
    public record MetricsMessage(String equipmentId, OffsetDateTime ts, List<MetricsResponse.Series> metrics) {}

    /** W-05 /topic/flow (11/22). M-17 제품 위치(at · products 그대로) + M-16 슬리팅 앞 코일 재고(제품 수) */
    public record FlowMessage(OffsetDateTime at, List<ProductPosition> products, int coilStockQty) {}
}
