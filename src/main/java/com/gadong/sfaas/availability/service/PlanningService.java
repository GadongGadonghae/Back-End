package com.gadong.sfaas.availability.service;

import com.gadong.sfaas.availability.dto.PlanningDtos.*;
import com.gadong.sfaas.global.response.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 생산 계획 · 재배정 · 계획정지 일정 서비스.
 *
 * <p>TODO(BE1): stub 이에요. production_target, reassignment_report/detail 과 계획정지 일정 테이블에서 읽도록 바꿔요.
 * 재배정 보고서 자동 생성(정지 30분 이상 → 손실 수량 → 여유 라인 추천)은 시간당 생산 능력이 정해진 뒤에 해요.
 */
@Service
public class PlanningService {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-18T09:30:00+09:00");

    /** G-01 */
    public List<ProductionSummaryResponse> productionSummary(LocalDate from, LocalDate to, String unit, String lineId) {
        return List.of(new ProductionSummaryResponse("2026-11-17", "A", 1500, 1240, 82.7));
    }

    /** G-02 */
    public List<PlanResponse> plans(LocalDate from, LocalDate to, String lineId) {
        return List.of(new PlanResponse("2026-11-17", "A", 1500));
    }

    /** G-03 */
    public PlanResponse setPlan(LocalDate date, PlanRequest request) {
        return new PlanResponse(date.toString(), request.lineId(), request.planQty());
    }

    /** R-01 */
    public PageResponse<ReassignmentReportResponse> reports(LocalDate from, LocalDate to, String status, int page, int size) {
        return PageResponse.of(List.of(new ReassignmentReportResponse(1, "B", "B-PR3", 42, null, "PENDING", T0)));
    }

    /** R-02 */
    public ReassignmentDetailResponse report(long reportId) {
        return new ReassignmentDetailResponse(reportId, "B", null,
                List.of(new ReassignmentDetailResponse.Target("D", 120)), "PENDING", null, null);
    }

    /** R-03 */
    public ApproveResponse approve(long reportId, ApproveRequest request) {
        return new ApproveResponse(reportId, "APPROVED", T0);
    }

    /** S-01 */
    public List<PlannedStopResponse> plannedStops(LocalDate from, LocalDate to, String lineId) {
        return List.of(new PlannedStopResponse(1, "A", "PL_SHIFT", T0.withHour(12), T0.withHour(13)));
    }

    /** S-02 */
    public PlannedStopCreatedResponse createPlannedStop(PlannedStopRequest request) {
        return new PlannedStopCreatedResponse(2);
    }
}
