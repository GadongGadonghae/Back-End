package com.gadong.sfaas.availability.dto;

import com.gadong.sfaas.report.AiReportResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 생산 계획 · 재배정 · 계획정지 API (API 명세서 v3: G-01 ~ G-03, R-01 ~ R-02, S-01 ~ S-03) */
public final class PlanningDtos {
    private PlanningDtos() {}

    /** G-01 */
    public record ProductionSummaryResponse(String period, String lineId, int planQty, int actualQty, double achievementRate) {}

    /** G-02 · G-03 */
    /** updatedAt·updatedBy = 마지막으로 저장한 시각·사람 이름 (11/20 추가) */
    public record PlanResponse(String date, String lineId, int planQty, OffsetDateTime updatedAt, String updatedBy) {}

    public record PlanRequest(String lineId, Integer planQty) {}

    /** R-01. code는 고장 사유 코드(downtime_event.code) */
    public record ReassignmentReportResponse(long reportId, long downtimeId, String sourceLineId, String equipmentId, long stopMin,
                                             int lossQty, OffsetDateTime createdAt, String code) {}

    /**
     * R-02 · R-03. 앞 5칸(reportId~details)은 처음 명세 그대로, 나머지는 v0.3.10 계획수정 보고서 화면용이에요.
     * kind = line(라인 안 설비 → 라인 전체 정지) · paint(도장 1대 → 다른 1대) · vision(대체 없음).
     * remainingSec = 기준 시각(baseAt)부터 자정까지, lossQty = 부족 수량, unmetQty = 나눠도 남는 목표 미달 예상
     */
    public record ReassignmentDetailResponse(long reportId, long downtimeId, String sourceLineId, int lossQty, List<Target> details,
                                             @Schema(description = "표시용 문서번호 AI-RPT-yyyyMMdd-보고서번호") String documentNo,
                                             String equipmentId, String code, String detail, OffsetDateTime startedAt,
                                             OffsetDateTime createdAt, String createdBy, String kind, OffsetDateTime baseAt,
                                             Long remainingSec, Double ctSec, Integer stoppedPlan, Integer stoppedDone,
                                             Integer targetQty, Integer unmetQty, AiContent ai) {
        /** targetLineId는 라인 ID, 도장 보고서면 설비 ID(PT1·PT2). share = 남은 능력 비율(%) */
        public record Target(String targetLineId, int spareCapacity, int addQty, Integer remainingPlan, double share) {}

        /**
         * AI 보고서 문장. mock=true면 템플릿 문장이고, LLM을 부르다 실패해서 대신한 거면 fallbackReason에 이유가 있어요.
         * additionalRequest = 화면의 "AI 추가 요청", requestInterpretation = 그 요청을 해석한 결과(제외·정지 라인, 우선 기준)
         */
        public record AiContent(String title, String summary, List<String> actions, List<String> risks, String markdown,
                                String model, boolean mock, String fallbackReason, String additionalRequest,
                                AiReportResponse.RequestInterpretation requestInterpretation) {}
    }

    /** R-03 계획수정(AI) 보고서 만들기. 누를 때마다 새 보고서. additionalRequest 예: "A라인은 제외하고 납기 우선" */
    public record ReassignmentCreateRequest(Long downtimeId, String additionalRequest) {}

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
