package com.gadong.sfaas.availability.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 생산 계획 · 재배정 · 계획정지 API (API 명세서 v3: G-01 ~ G-03, R-01 ~ R-02, S-01 ~ S-03) */
public final class PlanningDtos {
    private PlanningDtos() {}

    /** G-01 */
    public record ProductionSummaryResponse(String period, String lineId, int planQty, int actualQty, double achievementRate) {}

    /** G-02 · G-03 */
    public record PlanResponse(String date, String lineId, int planQty) {}

    public record PlanRequest(String lineId, Integer planQty) {}

    /** R-01 */
    public record ReassignmentReportResponse(long reportId, long downtimeId, String sourceLineId, String equipmentId, long stopMin,
                                             int lossQty, OffsetDateTime createdAt) {}

    /** R-02 */
    public record ReassignmentDetailResponse(long reportId, long downtimeId, String sourceLineId, int lossQty, List<Target> details) {
        public record Target(String targetLineId, int spareCapacity, int addQty) {}
    }

    /** S-01. equipmentIds는 그 라인의 설비 6대(슬리팅 1·프레스 4·용접 1) */
    public record PlannedStopResponse(long stopId, String lineId, List<String> equipmentIds, String reasonCode,
                                      OffsetDateTime startAt, OffsetDateTime endAt, boolean inProgress) {}

    /** S-02. startAt을 생략하면 지금부터예요 */
    public record PlannedStopRequest(String lineId, String reasonCode, OffsetDateTime startAt) {}

    public record PlannedStopStartedResponse(long stopId, String status, OffsetDateTime startAt) {}

    /** S-03. endAt을 생략하면 지금까지예요 */
    public record PlannedStopEndRequest(OffsetDateTime endAt) {}

    public record PlannedStopEndResponse(long stopId, OffsetDateTime endAt, long minutes) {}
}
