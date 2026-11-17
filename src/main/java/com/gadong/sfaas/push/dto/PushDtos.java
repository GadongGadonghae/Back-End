package com.gadong.sfaas.push.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/** WebSocket 푸시 메시지 (API 명세서 W-02 ~ W-04). W-01은 MonitoringDtos.EquipmentStatusResponse를 그대로 써요. */
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

    /** W-04 /topic/metrics/{equipmentId}. 명세서에 예시가 없어 E-01처럼 시각 + 측정값으로 보내요 */
    public record MetricsMessage(String equipmentId, OffsetDateTime ts, Map<String, Object> values) {}
}
