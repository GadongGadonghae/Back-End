package com.gadong.sfaas.availability.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 생산 계획 · 재배정 · 계획정지 API (API 명세서 v2: G-01 ~ G-03, R-01 ~ R-03, S-01 ~ S-02) */
public final class PlanningDtos {
    private PlanningDtos() {}

    /** G-01 */
    public record ProductionSummaryResponse(String period, String lineId, int planQty, int actualQty, double achievementRate) {}

    /** G-02 · G-03 */
    public record PlanResponse(String date, String lineId, int planQty) {}

    public record PlanRequest(String lineId, int planQty) {}

    /** R-01. lossQty 는 시간당 생산 능력이 정해져야 계산돼요(미정) */
    public record ReassignmentReportResponse(long reportId, String sourceLineId, String equipmentId, long stopMin,
                                             Long lossQty, String status, OffsetDateTime createdAt) {}

    /** R-02 */
    public record ReassignmentDetailResponse(long reportId, String sourceLineId, Long lossQty, List<Target> details,
                                             String status, String approvedBy, OffsetDateTime approvedAt) {
        public record Target(String targetLineId, int addQty) {}
    }

    /** R-03 */
    public record ApproveRequest(String approvedBy) {}

    public record ApproveResponse(long reportId, String status, OffsetDateTime approvedAt) {}

    /** S-01 · S-02 */
    public record PlannedStopResponse(long planId, String lineId, String reasonCode, OffsetDateTime startAt, OffsetDateTime endAt) {}

    public record PlannedStopRequest(String lineId, String reasonCode, OffsetDateTime startAt, OffsetDateTime endAt) {}

    public record PlannedStopCreatedResponse(long planId) {}
}
