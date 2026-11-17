package com.gadong.sfaas.availability.service;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.gadong.sfaas.availability.dto.PlanningDtos.*;
import com.gadong.sfaas.availability.entity.ProductionTarget;
import com.gadong.sfaas.availability.entity.ReassignmentReport;
import com.gadong.sfaas.availability.repository.ProductionTargetRepository;
import com.gadong.sfaas.availability.repository.ReassignmentDetailRepository;
import com.gadong.sfaas.availability.repository.ReassignmentReportRepository;
import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.global.util.Checks;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.global.util.Sql;
import com.gadong.sfaas.master.service.MasterService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 생산 계획 · 재배정 보고서 · 계획정지 서비스.
 *
 * <p>TODO(BE1): G-01은 집계 기준(도장 line_id가 v0.3.2에 생김)을 아직 반영하지 않은 stub이에요.
 * S-01~S-02는 명세(일정 등록: planId, startAt·endAt)와 DB(stop_group_id = 시작·종료 방식)가 달라 정해질 때까지 stub이에요.
 */
@Service
@Transactional(readOnly = true)
public class PlanningService {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-18T09:30:00+09:00");

    private static final String REPORT_FROM = " FROM reassignment_report r"
            + " JOIN downtime_event d ON d.downtime_id = r.downtime_id"
            + " JOIN equipment e ON e.equipment_id = d.equipment_id";

    private final NamedParameterJdbcTemplate jdbc;
    private final MasterService masterService;
    private final UserLookup users;
    private final ProductionTargetRepository targetRepository;
    private final ReassignmentReportRepository reportRepository;
    private final ReassignmentDetailRepository detailRepository;

    public PlanningService(NamedParameterJdbcTemplate jdbc, MasterService masterService, UserLookup users,
                           ProductionTargetRepository targetRepository, ReassignmentReportRepository reportRepository,
                           ReassignmentDetailRepository detailRepository) {
        this.jdbc = jdbc;
        this.masterService = masterService;
        this.users = users;
        this.targetRepository = targetRepository;
        this.reportRepository = reportRepository;
        this.detailRepository = detailRepository;
    }

    /** G-01 (stub) */
    public List<ProductionSummaryResponse> productionSummary(LocalDate from, LocalDate to, String unit, String lineId) {
        return List.of(new ProductionSummaryResponse("2026-11-17", "A", 1500, 1240, 82.7));
    }

    /** G-02 */
    public List<PlanResponse> plans(LocalDate from, LocalDate to, String lineId) {
        Checks.period(from, to);
        masterService.requireLine(lineId);
        List<ProductionTarget> targets = lineId == null
                ? targetRepository.findByTargetDateBetweenOrderByTargetDateAscLineIdAsc(from, to)
                : targetRepository.findByTargetDateBetweenAndLineIdOrderByTargetDateAsc(from, to, lineId);
        return targets.stream().map(PlanningService::plan).toList();
    }

    /** G-03: 같은 날짜·라인 목표가 있으면 수량만 바꾸고, 없으면 새로 만들어요 */
    @Transactional
    public PlanResponse setPlan(LocalDate date, PlanRequest request) {
        if (request == null || request.lineId() == null || request.planQty() == null || request.planQty() < 0) {
            throw invalid("lineId와 0 이상의 planQty가 필요합니다");
        }
        masterService.requireLine(request.lineId());
        ProductionTarget target = targetRepository.findById(new ProductionTarget.Key(date, request.lineId())).orElse(null);
        if (target == null) {
            // TODO(BE2 연동): 등록한 사람(created_by)은 JWT 로그인 사용자로 채워요
            target = targetRepository.save(new ProductionTarget(date, request.lineId(), request.planQty(), Kst.now()));
        } else {
            target.changeQty(request.planQty());
        }
        return plan(target);
    }

    /** R-01: 생성 시각(created_at) 기준 기간 조회, 최신순. status는 PENDING / APPROVED */
    public PageResponse<ReassignmentReportResponse> reports(LocalDate from, LocalDate to, String status, int page, int size) {
        Checks.period(from, to);
        Checks.page(page, size);
        if (status != null && !"PENDING".equals(status) && !"APPROVED".equals(status)) {
            throw invalid("status는 PENDING 또는 APPROVED입니다");
        }
        MapSqlParameterSource p = Sql.period(from, to);
        String where = REPORT_FROM + " WHERE r.created_at >= :from AND r.created_at < :to";
        if (status != null) {
            where += " AND r.approved = :approved";
            p.addValue("approved", "APPROVED".equals(status));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, p, Long.class);
        List<ReassignmentReportResponse> content = jdbc.query(
                "SELECT r.report_id, e.line_id, d.equipment_id, d.started_at, d.ended_at, r.loss_qty, r.approved, r.created_at" + where
                        + " ORDER BY r.created_at DESC, r.report_id DESC LIMIT :limit OFFSET :offset", Sql.paging(p, page, size),
                (rs, n) -> new ReassignmentReportResponse(rs.getLong("report_id"), rs.getString("line_id"),
                        rs.getString("equipment_id"),
                        Kst.minutes(rs.getObject("started_at", LocalDateTime.class), rs.getObject("ended_at", LocalDateTime.class)),
                        rs.getInt("loss_qty"), status(rs.getBoolean("approved")),
                        Kst.of(rs.getObject("created_at", LocalDateTime.class))));
        return new PageResponse<>(content, total);
    }

    /** R-02 */
    public ReassignmentDetailResponse report(long reportId) {
        ReassignmentReport report = findReport(reportId);
        String sourceLineId = jdbc.queryForObject(
                "SELECT e.line_id FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id WHERE d.downtime_id = :id",
                Map.of("id", report.getDowntimeId()), String.class);
        List<ReassignmentDetailResponse.Target> details = detailRepository.findByReportIdOrderByLineIdAsc(reportId).stream()
                .map(d -> new ReassignmentDetailResponse.Target(d.getLineId(), d.getAddedQty())).toList();
        return new ReassignmentDetailResponse(reportId, sourceLineId, report.getLossQty(), details,
                status(Boolean.TRUE.equals(report.getApproved())), report.getApprovedBy(), Kst.of(report.getApprovedAt()));
    }

    /** R-03. 이미 승인된 보고서는 처음 승인 기록을 그대로 돌려줘요 */
    @Transactional
    public ApproveResponse approve(long reportId, ApproveRequest request) {
        ReassignmentReport report = findReport(reportId);
        if (request == null || request.approvedBy() == null) throw invalid("approvedBy(사용자 번호)가 필요합니다");
        if (!users.exists(request.approvedBy())) throw invalid("사용자 " + request.approvedBy() + "를 찾을 수 없습니다");
        // TODO(BE2 연동): 승인 권한(관리자 role) 확인과 승인자는 JWT 로그인 사용자로 해요. 지금은 요청의 번호를 믿어요
        report.approve(request.approvedBy(), Kst.now());
        return new ApproveResponse(reportId, status(true), Kst.of(report.getApprovedAt()));
    }

    /** S-01 (stub) */
    public List<PlannedStopResponse> plannedStops(LocalDate from, LocalDate to, String lineId) {
        return List.of(new PlannedStopResponse(1, "A", "PL_SHIFT", T0.withHour(12), T0.withHour(13)));
    }

    /** S-02 (stub) */
    public PlannedStopCreatedResponse createPlannedStop(PlannedStopRequest request) {
        return new PlannedStopCreatedResponse(2);
    }

    private ReassignmentReport findReport(long reportId) {
        return reportRepository.findById(reportId).orElseThrow(() ->
                new BusinessException(ErrorCode.REASSIGNMENT_NOT_FOUND, "재배정 보고서 " + reportId + "를 찾을 수 없습니다"));
    }

    private static String status(boolean approved) {
        return approved ? "APPROVED" : "PENDING";
    }

    private static PlanResponse plan(ProductionTarget t) {
        return new PlanResponse(t.getTargetDate().toString(), t.getLineId(), t.getTargetQty());
    }
}
