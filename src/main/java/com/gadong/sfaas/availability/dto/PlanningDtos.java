package com.gadong.sfaas.availability.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 생산 계획 · 재배정 · 계획정지 API (API 명세서 v0.2: G-01 ~ G-03, R-01 ~ R-03, S-01 ~ S-02) */
public final class PlanningDtos {
    private PlanningDtos() {}

    /** G-01 */
    public record ProductionSummaryResponse(String period, String lineId, int planQty, int actualQty, double achievementRate) {}

    /** G-02 · G-03 */
    public record PlanResponse(String date, String lineId, int planQty) {}

    public record PlanRequest(String lineId, Integer planQty) {}

    /** R-01. status는 PENDING / APPROVED */
    public record ReassignmentReportResponse(long reportId, String sourceLineId, String equipmentId, long stopMin,
                                             int lossQty, String status, OffsetDateTime createdAt) {}

    /** R-02. 명세에 approvedBy 자료형이 없어 users 번호(user_id)로 내려요 */
    public record ReassignmentDetailResponse(long reportId, String sourceLineId, int lossQty, List<Target> details,
                                             String status, Integer approvedBy, OffsetDateTime approvedAt) {
        public record Target(String targetLineId, int addQty) {}
    }

    /** R-03 */
    public record ApproveRequest(Integer approvedBy) {}

    public record ApproveResponse(long reportId, String status, OffsetDateTime approvedAt) {}

    /** S-01 · S-02 (stub) */
    public record PlannedStopResponse(long planId, String lineId, String reasonCode, OffsetDateTime startAt, OffsetDateTime endAt) {}

    public record PlannedStopRequest(String lineId, String reasonCode, OffsetDateTime startAt, OffsetDateTime endAt) {}

    public record PlannedStopCreatedResponse(long planId) {}
}
