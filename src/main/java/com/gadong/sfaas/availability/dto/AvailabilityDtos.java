package com.gadong.sfaas.availability.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 가동률 화면 API 응답·요청 (API 명세서 v3: U-01 ~ U-12) */
public final class AvailabilityDtos {
    private AvailabilityDtos() {}

    /** U-01. 가동시간·판정 제품이 없어 못 구하면 performance, oee 는 null. lineYield(출고 양품 ÷ 슬리팅 투입)는 OEE와 별개, 투입이 없으면 null */
    public record SummaryResponse(double target, double actual, Double oee, double availability, Double performance,
                                  double quality, long plannedMin, long unplannedStopMin, Long lossQty, Double lineYield) {}

    /** U-02. actual = availability. 못 구하는 값은 null */
    public record TrendResponse(String period, double target, double actual, Double oee, double availability,
                                Double performance, Double quality) {}

    /** U-03 */
    public record ByProcessResponse(String process, double actual, Double oee, long unplannedStopMin) {}

    /** U-04 */
    public record ByLineResponse(String lineId, double actual, int stopCount, Double oee, Double lineYield) {}

    /** U-05 */
    public record ByEquipmentResponse(String equipmentId, double actual, Double oee) {}

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
                                        boolean inProgress, String detail, Integer actionUserId, String actionUserName,
                                        OffsetDateTime actionAt, String memo) {}

    /** U-09: 조치 담당자는 이름이 아니라 번호(동명이인 대비). 응답에 이름을 붙여요 */
    public record DowntimeEventUpdateRequest(String reasonCode, String memo, Integer actionUserId) {}

    public record DowntimeEventUpdateResponse(long eventId, String reasonCode, Integer actionUserId, String actionUserName) {}

    /** U-10 */
    public record DowntimeTopResponse(String equipmentId, long totalMin, int count) {}

    /** U-11 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record YieldResponse(String period, String key, int total, int good, double yieldRate) {}

    /** U-12 */
    public record StatusSegmentResponse(String status, OffsetDateTime startAt, OffsetDateTime endAt, String reasonCode) {}
}
