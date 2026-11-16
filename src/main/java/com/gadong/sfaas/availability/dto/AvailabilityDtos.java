package com.gadong.sfaas.availability.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 가동률 화면 API 응답·요청 (API 명세서 v2: U-01 ~ U-12) */
public final class AvailabilityDtos {
    private AvailabilityDtos() {}

    /** U-01. 시간당 생산 능력이 미정이라 performance, oee, lossQty 는 당분간 null */
    public record SummaryResponse(double target, double actual, Double oee, double availability, Double performance,
                                  double quality, long plannedMin, long unplannedStopMin, Long lossQty) {}

    /** U-02 */
    public record TrendResponse(String period, double target, double actual) {}

    /** U-03 */
    public record ByProcessResponse(String process, double actual, Double oee, long unplannedStopMin) {}

    /** U-04 */
    public record ByLineResponse(String lineId, double actual, int stopCount) {}

    /** U-05 */
    public record ByEquipmentResponse(String equipmentId, double actual) {}

    /** U-06 */
    public record ByWeekdayResponse(@Schema(example = "MON") String weekday, double actual, int stopCount) {}

    /** U-07 */
    public record DowntimeSummaryResponse(String from, String to, long totalMin, List<ReasonStat> byReason,
                                          List<Map<String, Object>> byGroup) {
        public record ReasonStat(String reasonCode, long minutes, int count, double ratio) {}
    }

    /** U-08 */
    public record DowntimeEventResponse(long eventId, String lineId, String equipmentId, String process, String status,
                                        String reasonCode, OffsetDateTime startAt, OffsetDateTime endAt, long minutes,
                                        boolean inProgress, String actionBy, String memo) {}

    /** U-09 */
    public record DowntimeEventUpdateRequest(String reasonCode, String memo, String actionBy) {}

    public record DowntimeEventUpdateResponse(long eventId, String reasonCode, String actionBy) {}

    /** U-10 */
    public record DowntimeTopResponse(String equipmentId, long totalMin, int count) {}

    /** U-11 */
    public record YieldResponse(String period, int total, int good, double yieldRate) {}

    /** U-12 */
    public record StatusSegmentResponse(String status, OffsetDateTime startAt, OffsetDateTime endAt, String reasonCode) {}
}
