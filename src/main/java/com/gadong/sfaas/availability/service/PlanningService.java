package com.gadong.sfaas.availability.service;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.gadong.sfaas.availability.dto.PlanningDtos.*;
import com.gadong.sfaas.availability.entity.ProductionTarget;
import com.gadong.sfaas.availability.repository.ProductionTargetRepository;
import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.global.util.Checks;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.global.util.Periods;
import com.gadong.sfaas.global.util.Sql;
import com.gadong.sfaas.master.entity.DowntimeCode;
import com.gadong.sfaas.master.repository.DowntimeCodeRepository;
import com.gadong.sfaas.master.service.MasterService;
import com.gadong.sfaas.monitoring.service.ProductionStats;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 생산 계획 · 재배정 보고서 · 계획정지 서비스.
 */
@Service
@Transactional(readOnly = true)
public class PlanningService {

    private static final String REPORT_FROM = " FROM reassignment_report r"
            + " JOIN downtime_event d ON d.downtime_id = r.downtime_id"
            + " JOIN equipment e ON e.equipment_id = d.equipment_id";

    private final NamedParameterJdbcTemplate jdbc;
    private final MasterService masterService;
    private final DowntimeCodeRepository downtimeCodeRepository;
    private final ProductionStats productionStats;
    private final ProductionTargetRepository targetRepository;

    public PlanningService(NamedParameterJdbcTemplate jdbc, MasterService masterService, DowntimeCodeRepository downtimeCodeRepository, ProductionStats productionStats,
                           ProductionTargetRepository targetRepository) {
        this.jdbc = jdbc;
        this.masterService = masterService;
        this.downtimeCodeRepository = downtimeCodeRepository;
        this.productionStats = productionStats;
        this.targetRepository = targetRepository;
    }

    /** G-01. 실적 = 출고 양품 수(검사 OK, 대시보드 M-07 ok와 같음, 11/18 변경. 예전엔 검사 수 + 공정 불량 수). 계획이나 실적이 있는 (구간, 라인)마다 한 줄이에요 */
    public List<ProductionSummaryResponse> productionSummary(LocalDate from, LocalDate to, String unit, String lineId) {
        Checks.period(from, to);
        Periods.unit(unit);
        masterService.requireLine(lineId);
        Map<String, long[]> sums = new TreeMap<>(); // "구간|라인" -> {계획, 실적}
        for (ProductionTarget t : lineId == null
                ? targetRepository.findByTargetDateBetweenOrderByTargetDateAscLineIdAsc(from, to)
                : targetRepository.findByTargetDateBetweenAndLineIdOrderByTargetDateAsc(from, to, lineId)) {
            sums.computeIfAbsent(key(t.getTargetDate(), t.getLineId(), unit), k -> new long[2])[0] += t.getTargetQty();
        }
        for (ProductionStats.Row r : productionStats.daily(from, to, lineId)) {
            if (r.lineId() != null) sums.computeIfAbsent(key(r.day(), r.lineId(), unit), k -> new long[2])[1] += r.ok();
        }
        return sums.entrySet().stream().map(e -> {
            String[] k = e.getKey().split("\\|");
            long plan = e.getValue()[0], actual = e.getValue()[1];
            return new ProductionSummaryResponse(k[0], k[1], (int) plan, (int) actual, plan == 0 ? 0 : Math.round(actual * 1000.0 / plan) / 10.0);
        }).toList();
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

    /** R-01: 생성 시각(created_at) 기준 기간 조회, 최신순. 보고서는 고장(D) 건에서 R-03으로 만들어요(승인 없음). R-02·R-03은 ReplanService */
    public PageResponse<ReassignmentReportResponse> reports(LocalDate from, LocalDate to, int page, int size) {
        Checks.period(from, to);
        Checks.page(page, size);
        MapSqlParameterSource p = Sql.period(from, to);
        String where = REPORT_FROM + " WHERE r.created_at >= :from AND r.created_at < :to";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, p, Long.class);
        List<ReassignmentReportResponse> content = jdbc.query(
                "SELECT r.report_id, r.downtime_id, e.line_id, d.equipment_id, d.started_at, d.ended_at, r.loss_qty, r.created_at, d.code" + where
                        + " ORDER BY r.created_at DESC, r.report_id DESC LIMIT :limit OFFSET :offset", Sql.paging(p, page, size),
                (rs, n) -> new ReassignmentReportResponse(rs.getLong("report_id"), rs.getLong("downtime_id"), rs.getString("line_id"),
                        rs.getString("equipment_id"),
                        Kst.minutes(rs.getObject("started_at", LocalDateTime.class), rs.getObject("ended_at", LocalDateTime.class)),
                        rs.getInt("loss_qty"), Kst.of(rs.getObject("created_at", LocalDateTime.class)), rs.getString("code")));
        return new PageResponse<>(content, total, page, size);
    }

    /** S-01. 라인 정지 1건(stop_group_id)을 stopId 하나로 묶고, 그 라인의 설비를 equipmentIds로 줘요 */
    public List<PlannedStopResponse> plannedStops(LocalDate from, LocalDate to, String lineId, Boolean inProgress) {
        Checks.period(from, to);
        masterService.requireLine(lineId);
        MapSqlParameterSource p = Sql.period(from, to);
        StringBuilder sql = new StringBuilder("SELECT d.stop_group_id, e.line_id, e.equipment_id, d.code, d.started_at, d.ended_at"
                + " FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id JOIN process pr ON pr.process_id = e.process_id"
                + " WHERE d.stop_group_id IS NOT NULL AND d.started_at < :to AND (d.ended_at IS NULL OR d.ended_at > :from)");
        Sql.eq(sql, p, "e.line_id", "lineId", lineId);
        sql.append(" ORDER BY d.started_at DESC, d.stop_group_id DESC, pr.seq, e.seq_no");
        Map<Long, PlannedStopResponse> stops = new LinkedHashMap<>();
        jdbc.query(sql.toString(), p, (RowCallbackHandler) rs -> {
            long id = rs.getLong("stop_group_id");
            PlannedStopResponse stop = stops.get(id);
            if (stop == null) {
                LocalDateTime end = rs.getObject("ended_at", LocalDateTime.class);
                stop = new PlannedStopResponse(id, rs.getString("line_id"), new ArrayList<>(), rs.getString("code"),
                        Kst.of(rs.getObject("started_at", LocalDateTime.class)), Kst.of(end), end == null);
                stops.put(id, stop);
            }
            stop.equipmentIds().add(rs.getString("equipment_id"));
        });
        return stops.values().stream().filter(s -> inProgress == null || s.inProgress() == inProgress).toList();
    }

    /** S-02. 라인 설비 6대에 같은 stop_group_id로 한 줄씩 넣어요. 라인에 진행 중인 계획정지가 있으면 409 */
    @Transactional
    public PlannedStopStartedResponse startPlannedStop(PlannedStopRequest request) {
        if (request == null || request.lineId() == null || request.reasonCode() == null) throw invalid("lineId와 reasonCode가 필요합니다");
        masterService.requireLine(request.lineId());
        DowntimeCode code = downtimeCodeRepository.findById(request.reasonCode())
                .orElseThrow(() -> invalid("비가동 사유 코드 " + request.reasonCode() + "가 없습니다"));
        if (!Boolean.TRUE.equals(code.getPlanned())) throw invalid("계획정지 사유(PL_*)만 쓸 수 있습니다");
        List<String> equipmentIds = jdbc.queryForList("SELECT e.equipment_id FROM equipment e WHERE e.line_id = :lineId AND e.is_active = TRUE"
                + " AND e.process_id IN ('SLITTING', 'PRESS', 'WELDING')", Map.of("lineId", request.lineId()), String.class);
        if (equipmentIds.isEmpty()) throw invalid("라인 " + request.lineId() + "에 설비가 없습니다");
        long running = jdbc.queryForObject("SELECT COUNT(*) FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                + " WHERE e.line_id = :lineId AND d.stop_group_id IS NOT NULL AND d.ended_at IS NULL", Map.of("lineId", request.lineId()), Long.class);
        if (running > 0) throw new BusinessException(ErrorCode.PLANNED_STOP_CONFLICT, "라인 " + request.lineId() + "에 진행 중인 계획정지가 있습니다");

        LocalDateTime start = request.startAt() == null ? Kst.now() : Kst.local(request.startAt());
        long stopId = jdbc.queryForObject("SELECT nextval('stop_group_seq')", Map.of(), Long.class);
        // TODO(BE2 연동): 시작한 관리자(action_user_id)는 JWT 로그인 사용자로 채워요
        jdbc.batchUpdate("INSERT INTO downtime_event(equipment_id, status, code, started_at, stop_group_id) VALUES (:eq, 'S', :code, :start, :gid)",
                equipmentIds.stream().map(eq -> new MapSqlParameterSource().addValue("eq", eq).addValue("code", code.getCode())
                        .addValue("start", start).addValue("gid", stopId)).toArray(MapSqlParameterSource[]::new));
        return new PlannedStopStartedResponse(stopId, "S", Kst.of(start));
    }

    /** S-03. 종료 시각을 정하면 그 라인 설비의 비가동 6줄이 함께 닫혀요 */
    @Transactional
    public PlannedStopEndResponse endPlannedStop(long stopId, PlannedStopEndRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT started_at, ended_at FROM downtime_event WHERE stop_group_id = :id",
                Map.of("id", stopId));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.PLANNED_STOP_NOT_FOUND, "계획정지 " + stopId + "를 찾을 수 없습니다");
        if (rows.stream().noneMatch(r -> r.get("ended_at") == null)) throw invalid("이미 종료된 계획정지입니다");
        LocalDateTime start = Kst.local(rows.get(0).get("started_at"));
        LocalDateTime end = request == null || request.endAt() == null ? Kst.now() : Kst.local(request.endAt());
        if (end.isBefore(start)) throw invalid("endAt은 시작 시각보다 빠를 수 없습니다");
        jdbc.update("UPDATE downtime_event SET ended_at = :end WHERE stop_group_id = :id AND ended_at IS NULL", Map.of("end", end, "id", stopId));
        return new PlannedStopEndResponse(stopId, Kst.of(end), Kst.minutes(start, end));
    }

    /** G-01 묶음 키: "구간|라인" */
    private static String key(LocalDate day, String lineId, String unit) {
        return Periods.label(Periods.start(day, unit), unit) + "|" + lineId;
    }

    private static PlanResponse plan(ProductionTarget t) {
        return new PlanResponse(t.getTargetDate().toString(), t.getLineId(), t.getTargetQty());
    }
}
