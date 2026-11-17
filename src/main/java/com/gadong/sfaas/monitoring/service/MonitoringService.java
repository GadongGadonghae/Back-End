package com.gadong.sfaas.monitoring.service;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.global.util.Checks;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.global.util.Sql;
import com.gadong.sfaas.judge.entity.Alarm;
import com.gadong.sfaas.judge.repository.AlarmRepository;
import com.gadong.sfaas.master.entity.Equipment;
import com.gadong.sfaas.master.entity.Line;
import com.gadong.sfaas.master.entity.PaintRouteHistory;
import com.gadong.sfaas.master.entity.Threshold;
import com.gadong.sfaas.master.repository.LineRepository;
import com.gadong.sfaas.master.repository.PaintRouteHistoryRepository;
import com.gadong.sfaas.master.repository.ProcessRepository;
import com.gadong.sfaas.master.repository.ThresholdRepository;
import com.gadong.sfaas.master.service.MasterService;
import com.gadong.sfaas.master.service.UserLookup;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모니터링 화면 API 서비스. 조회는 여러 테이블을 묶어야 해서 SQL(JdbcTemplate)로, 수정은 엔티티로 해요.
 */
@Service
@Transactional(readOnly = true)
public class MonitoringService {

    private static final List<String> RSID = List.of("R", "S", "I", "D", "DC");

    private final NamedParameterJdbcTemplate jdbc;
    private final MasterService masterService;
    private final LineRepository lineRepository;
    private final ProcessRepository processRepository;
    private final ThresholdRepository thresholdRepository;
    private final AlarmRepository alarmRepository;
    private final PaintRouteHistoryRepository paintRouteHistoryRepository;
    private final UserLookup users;
    private final ProductionStats productionStats;

    public MonitoringService(NamedParameterJdbcTemplate jdbc, MasterService masterService, LineRepository lineRepository,
                             ProcessRepository processRepository, ThresholdRepository thresholdRepository,
                             AlarmRepository alarmRepository, PaintRouteHistoryRepository paintRouteHistoryRepository,
                             UserLookup users, ProductionStats productionStats) {
        this.jdbc = jdbc;
        this.masterService = masterService;
        this.lineRepository = lineRepository;
        this.processRepository = processRepository;
        this.thresholdRepository = thresholdRepository;
        this.alarmRepository = alarmRepository;
        this.paintRouteHistoryRepository = paintRouteHistoryRepository;
        this.users = users;
        this.productionStats = productionStats;
    }

    /** M-01 */
    public List<EquipmentStatusResponse> status(String lineId) {
        masterService.requireLine(lineId);
        return equipmentStatuses(lineId, null);
    }

    /** M-02: 라인에 속한 설비만 세요(공용 도장·검사 제외). 상태 기록이 없는 설비는 세지 않아요. 우선순위 D > DC > S > I > R */
    public List<LineSummaryResponse> linesSummary() {
        Map<String, int[]> counts = new TreeMap<>();
        lineRepository.findAll().forEach(l -> counts.put(l.getLineId(), new int[5]));
        for (EquipmentStatusResponse s : equipmentStatuses(null, null)) {
            int i = s.status() == null ? -1 : RSID.indexOf(s.status()); // List.of는 null 검색에서 예외를 던져요
            if (i >= 0 && s.lineId() != null && counts.containsKey(s.lineId())) counts.get(s.lineId())[i]++;
        }
        return counts.entrySet().stream().map(e -> {
            int[] c = e.getValue(); // {R, S, I, D, DC}
            String worst = c[3] > 0 ? "D" : c[4] > 0 ? "DC" : c[1] > 0 ? "S" : c[2] > 0 ? "I" : c[0] > 0 ? "R" : null;
            return new LineSummaryResponse(e.getKey(), c[0], c[1], c[2], c[3], c[4], worst);
        }).toList();
    }

    /** M-03: 상·하한은 threshold(M-10과 같은 값)예요. 명세에서 기준이 미정이라 바뀔 수 있어요 */
    public MetricsResponse metrics(String equipmentId, LocalDate from, LocalDate to, String metric, String interval) {
        Checks.period(from, to);
        if (!"raw".equals(interval) && !"5s".equals(interval)) throw invalid("interval은 raw 또는 5s입니다");
        Equipment equipment = masterService.equipment(equipmentId);
        Optional<ProcessData.Table> table = ProcessData.of(equipment.getProcessId());
        if (table.isEmpty()) return new MetricsResponse(equipmentId, List.of()); // 검사기는 측정 항목이 없어요
        List<String> columns = table.get().columns().stream()
                .filter(c -> metric == null || ProcessData.camel(c).equals(metric)).toList();
        if (columns.isEmpty()) throw invalid("metric " + metric + "은 설비 " + equipmentId + "의 측정 항목이 아닙니다");

        Map<String, Threshold> limits = thresholdRepository.findByEquipmentId(equipmentId).stream()
                .collect(Collectors.toMap(Threshold::getItemName, Function.identity(), (a, b) -> a));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT measured_at, result, " + String.join(", ", columns) + " FROM " + table.get().name()
                        + " WHERE equipment_id = :eq AND measured_at >= :from AND measured_at < :to ORDER BY measured_at",
                Map.of("eq", equipmentId, "from", Kst.start(from), "to", Kst.endExclusive(to)));

        List<MetricsResponse.Series> series = new ArrayList<>();
        for (String c : columns) {
            Threshold t = limits.get(c);
            series.add(new MetricsResponse.Series(ProcessData.camel(c), t == null ? null : t.getUnit(),
                    t == null ? null : toDouble(t.getLowerLimit()), t == null ? null : toDouble(t.getUpperLimit()),
                    "raw".equals(interval) ? rawPoints(rows, c) : fiveSecondPoints(rows, c)));
        }
        return new MetricsResponse(equipmentId, series);
    }

    private static final String ALARM_COLUMNS = "a.alarm_id, a.equipment_id, a.item_name, a.measured_value, a.lower_limit,"
            + " a.upper_limit, a.level, a.message, a.raised_at, a.cleared_at, a.acked_at";

    /** M-04 */
    public List<AlarmResponse> activeAlarms(String lineId) {
        masterService.requireLine(lineId);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT " + ALARM_COLUMNS + " FROM alarm a"
                + " JOIN equipment e ON e.equipment_id = a.equipment_id WHERE a.cleared_at IS NULL");
        Sql.eq(sql, p, "e.line_id", "lineId", lineId);
        sql.append(" ORDER BY a.raised_at DESC, a.alarm_id DESC");
        return jdbc.query(sql.toString(), p, (rs, n) -> new AlarmResponse(rs.getLong("alarm_id"), rs.getString("equipment_id"),
                itemName(rs.getString("item_name")), toDouble(rs.getBigDecimal("measured_value")),
                toDouble(rs.getBigDecimal("lower_limit")), toDouble(rs.getBigDecimal("upper_limit")), rs.getString("level"),
                rs.getString("message"), Kst.of(rs.getObject("raised_at", LocalDateTime.class)),
                Kst.of(rs.getObject("cleared_at", LocalDateTime.class))));
    }

    /** M-05 */
    public PageResponse<AlarmHistoryResponse> alarms(LocalDate from, LocalDate to, String equipmentId, int page, int size) {
        Checks.period(from, to);
        Checks.page(page, size);
        if (equipmentId != null) masterService.equipment(equipmentId);
        MapSqlParameterSource p = Sql.period(from, to);
        StringBuilder where = new StringBuilder(" FROM alarm a WHERE a.raised_at >= :from AND a.raised_at < :to");
        Sql.eq(where, p, "a.equipment_id", "eq", equipmentId);
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, p, Long.class);
        List<AlarmHistoryResponse> content = jdbc.query(
                "SELECT " + ALARM_COLUMNS + where + " ORDER BY a.raised_at DESC, a.alarm_id DESC LIMIT :limit OFFSET :offset",
                Sql.paging(p, page, size),
                (rs, n) -> new AlarmHistoryResponse(rs.getLong("alarm_id"), rs.getString("equipment_id"), rs.getString("level"),
                        rs.getString("message"), itemName(rs.getString("item_name")), toDouble(rs.getBigDecimal("measured_value")),
                        toDouble(rs.getBigDecimal("lower_limit")), toDouble(rs.getBigDecimal("upper_limit")),
                        Kst.of(rs.getObject("raised_at", LocalDateTime.class)), Kst.of(rs.getObject("cleared_at", LocalDateTime.class)),
                        Kst.of(rs.getObject("acked_at", LocalDateTime.class))));
        return new PageResponse<>(content, total);
    }

    /** M-06. 이미 확인한 알람은 처음 확인한 사람·시각을 돌려줘요 */
    @Transactional
    public AckResponse ack(long alarmId, AckRequest request) {
        if (request == null || request.ackUserId() == null) throw invalid("ackUserId가 필요합니다");
        if (!users.exists(request.ackUserId())) throw invalid("사용자 " + request.ackUserId() + "를 찾을 수 없습니다");
        Alarm alarm = alarmRepository.findById(alarmId).orElseThrow(() ->
                new BusinessException(ErrorCode.ALARM_NOT_FOUND, "알람 " + alarmId + "를 찾을 수 없습니다"));
        alarm.ack(request.ackUserId(), Kst.now());
        return new AckResponse(alarmId, alarm.getAckedBy(), Kst.of(alarm.getAckedAt()));
    }

    /** M-10: 기준치는 설비별이라 equipmentId로 좁힐 수 있어요 */
    public List<ThresholdResponse> thresholds(String process, String equipmentId) {
        if (equipmentId != null) masterService.equipment(equipmentId);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT t.threshold_id, t.equipment_id, e.process_id, t.item_name, t.lower_limit, t.upper_limit"
                + " FROM threshold t JOIN equipment e ON e.equipment_id = t.equipment_id WHERE 1 = 1");
        Sql.eq(sql, p, "e.process_id", "process", process == null ? null : process.toUpperCase());
        Sql.eq(sql, p, "t.equipment_id", "eq", equipmentId);
        sql.append(" ORDER BY t.threshold_id");
        return jdbc.query(sql.toString(), p, (rs, n) -> new ThresholdResponse(rs.getInt("threshold_id"), rs.getString("equipment_id"),
                rs.getString("process_id"), ProcessData.camel(rs.getString("item_name")), toDouble(rs.getBigDecimal("lower_limit")),
                toDouble(rs.getBigDecimal("upper_limit"))));
    }

    /** M-11 */
    @Transactional
    public ThresholdUpdateResponse updateThreshold(int id, ThresholdUpdateRequest request) {
        if (request == null || request.low() == null || request.high() == null || request.low() > request.high()) {
            throw invalid("low와 high가 필요하고 low는 high보다 클 수 없습니다");
        }
        Threshold threshold = thresholdRepository.findById(id).orElseThrow(() ->
                new BusinessException(ErrorCode.THRESHOLD_NOT_FOUND, "알람 기준치 " + id + "를 찾을 수 없습니다"));
        // TODO(BE2 연동): 수정한 사람(updated_by)은 JWT 로그인 사용자로 채워요
        threshold.update(BigDecimal.valueOf(request.low()), BigDecimal.valueOf(request.high()), Kst.now());
        return new ThresholdUpdateResponse(id, request.low(), request.high(), Kst.of(threshold.getUpdatedAt()));
    }

    /** M-07 */
    public ProductionCountsResponse counts(LocalDate date, String lineId, String groupBy) {
        return productionStats.counts(date, lineId, groupBy);
    }

    /** M-08 */
    public PageResponse<InspectionResponse> inspections(LocalDate from, LocalDate to, String lineId, String paintId,
                                                        String result, int page, int size) {
        Checks.period(from, to);
        Checks.page(page, size);
        Checks.result(result);
        masterService.requireLine(lineId);
        MapSqlParameterSource p = Sql.period(from, to);
        StringBuilder where = new StringBuilder(" FROM inspection_result WHERE inspected_at >= :from AND inspected_at < :to");
        Sql.eq(where, p, "line_id", "lineId", lineId);
        Sql.eq(where, p, "paint_equipment_id", "paintId", paintId);
        Sql.eq(where, p, "result", "result", result);
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, p, Long.class);
        List<InspectionResponse> content = jdbc.query(
                "SELECT inspection_id, product_id, line_id, paint_equipment_id, result, inspected_at" + where
                        + " ORDER BY inspected_at DESC, inspection_id DESC LIMIT :limit OFFSET :offset", Sql.paging(p, page, size),
                (rs, n) -> new InspectionResponse(rs.getLong("inspection_id"), rs.getString("product_id"), rs.getString("line_id"),
                        rs.getString("paint_equipment_id"), rs.getString("result"),
                        Kst.of(rs.getObject("inspected_at", LocalDateTime.class))));
        return new PageResponse<>(content, total);
    }

    /** M-09 */
    @Transactional
    public PaintRouteResponse changePaintRoute(String lineId, PaintRouteRequest request) {
        Line line = lineRepository.findById(lineId).orElseThrow(() ->
                new BusinessException(ErrorCode.LINE_NOT_FOUND, "라인 " + lineId + "를 찾을 수 없습니다"));
        if (request == null || request.paintId() == null) throw invalid("paintId가 필요합니다");
        if (request.reason() != null && request.reason().length() > 200) throw invalid("reason은 200자 이하입니다");
        Equipment paint = masterService.equipment(request.paintId());
        if (!"PAINTING".equals(paint.getProcessId())) throw invalid(request.paintId() + "는 도장 설비가 아닙니다");

        LocalDateTime now = Kst.now();
        // TODO(BE2 연동): 바꾼 사람(changed_by)은 JWT 로그인 사용자로 채워요
        paintRouteHistoryRepository.save(new PaintRouteHistory(lineId, line.getCurrentPaintId(), paint.getEquipmentId(),
                request.reason(), now));
        line.changePaint(paint.getEquipmentId());
        // TODO(MQTT 연동): Q-03 sfaas/cmd/route/{lineId} 로 {lineId, paintId, requestedBy, ts} 를 Node-RED에 보내요
        return new PaintRouteResponse(lineId, line.getCurrentPaintId(), Kst.of(now));
    }

    /** M-12 */
    public List<EquipmentStatusResponse> processEquipments(String process, String lineId) {
        String code = process.toUpperCase();
        if (!processRepository.existsById(code)) throw invalid("공정 " + process + "가 없습니다");
        masterService.requireLine(lineId);
        return equipmentStatuses(lineId, code);
    }

    /** M-13 */
    public List<WipResponse> wip(String lineId) {
        return productionStats.wip(lineId);
    }

    /** E-01. 공정별 테이블에서 각각 최신순으로 필요한 만큼만 읽고 합쳐서 페이지를 나눠요 */
    public PageResponse<EquipmentDataResponse> equipmentData(LocalDate from, LocalDate to, String lineId, String process,
                                                             String equipmentId, String result, int page, int size) {
        Checks.period(from, to);
        Checks.page(page, size);
        Checks.result(result);
        masterService.requireLine(lineId);
        List<ProcessData.Table> tables = process == null ? ProcessData.TABLES
                : List.of(ProcessData.of(process).orElseThrow(() -> invalid("process는 SLITTING, PRESS, WELDING, PAINTING 중 하나입니다")));
        if (equipmentId != null) {
            String eqProcess = masterService.equipment(equipmentId).getProcessId();
            tables = tables.stream().filter(t -> t.process().equals(eqProcess)).toList();
        }

        long need = (long) (page + 1) * size;
        long total = 0;
        List<EquipmentDataResponse> merged = new ArrayList<>();
        for (ProcessData.Table t : tables) {
            MapSqlParameterSource p = Sql.period(from, to);
            StringBuilder where = new StringBuilder(" FROM " + t.name() + " d JOIN equipment e ON e.equipment_id = d.equipment_id"
                    + " WHERE d.measured_at >= :from AND d.measured_at < :to");
            Sql.eq(where, p, "e.line_id", "lineId", lineId);
            Sql.eq(where, p, "d.equipment_id", "eq", equipmentId);
            Sql.eq(where, p, "d.result", "result", result);
            total += jdbc.queryForObject("SELECT COUNT(*)" + where, p, Long.class);
            p.addValue("limit", need);
            String cols = t.columns().stream().map(c -> "d." + c).collect(Collectors.joining(", "));
            for (Map<String, Object> row : jdbc.queryForList("SELECT d.equipment_id, d.measured_at, d.result, " + cols + where
                    + " ORDER BY d.measured_at DESC, d.id DESC LIMIT :limit", p)) {
                merged.add(new EquipmentDataResponse(Kst.of(Kst.local(row.get("measured_at"))), (String) row.get("equipment_id"),
                        t.process(), (String) row.get("result"), ProcessData.values(row, t)));
            }
        }
        merged.sort(Comparator.comparing(EquipmentDataResponse::ts).reversed());
        int fromIndex = (int) Math.min((long) page * size, merged.size());
        int toIndex = (int) Math.min(need, merged.size());
        return new PageResponse<>(new ArrayList<>(merged.subList(fromIndex, toIndex)), total);
    }

    /** M-01·M-12 공통: 열린 상태 구간(ended_at IS NULL) + 그 구간의 비가동 사유(downtime_id) + 마지막 측정값 */
    private List<EquipmentStatusResponse> equipmentStatuses(String lineId, String process) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
                SELECT e.equipment_id, e.line_id, e.process_id, s.status, s.started_at, d.code AS reason_code
                FROM equipment e
                JOIN process p ON p.process_id = e.process_id
                LEFT JOIN equipment_status_history s ON s.equipment_id = e.equipment_id AND s.ended_at IS NULL
                LEFT JOIN downtime_event d ON d.downtime_id = s.downtime_id
                WHERE e.is_active = TRUE""");
        Sql.eq(sql, p, "e.line_id", "lineId", lineId);
        Sql.eq(sql, p, "e.process_id", "process", process);
        sql.append(" ORDER BY CASE WHEN e.line_id IS NULL THEN 1 ELSE 0 END, e.line_id, p.seq, e.seq_no, s.started_at DESC");

        Map<String, Map<String, Object>> lastValues = lastValues();
        Map<String, EquipmentStatusResponse> byId = new LinkedHashMap<>();
        jdbc.query(sql.toString(), p, (RowCallbackHandler) rs -> {
            String id = rs.getString("equipment_id");
            if (byId.containsKey(id)) return; // 열린 구간이 여러 개면 가장 최근 것만 써요
            String status = rs.getString("status") == null ? null : rs.getString("status").trim();
            byId.put(id, new EquipmentStatusResponse(id, rs.getString("line_id"), rs.getString("process_id"), status,
                    Kst.of(rs.getObject("started_at", LocalDateTime.class)),
                    status == null || "R".equals(status) ? null : rs.getString("reason_code"),
                    lastValues.getOrDefault(id, Map.of())));
        });
        return new ArrayList<>(byId.values());
    }

    private Map<String, Map<String, Object>> lastValues() {
        Map<String, Map<String, Object>> last = new HashMap<>();
        for (ProcessData.Table t : ProcessData.TABLES) {
            String sql = "SELECT equipment_id, " + String.join(", ", t.columns()) + " FROM " + t.name()
                    + " WHERE (equipment_id, measured_at) IN (SELECT equipment_id, MAX(measured_at) FROM " + t.name()
                    + " GROUP BY equipment_id)";
            for (Map<String, Object> row : jdbc.queryForList(sql, Map.of())) {
                last.putIfAbsent((String) row.get("equipment_id"), ProcessData.values(row, t));
            }
        }
        return last;
    }

    private static List<MetricsResponse.Point> rawPoints(List<Map<String, Object>> rows, String column) {
        List<MetricsResponse.Point> points = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get(column) instanceof Number v) {
                points.add(new MetricsResponse.Point(Kst.of(Kst.local(row.get("measured_at"))), v.doubleValue(),
                        (String) row.get("result")));
            }
        }
        return points;
    }

    /** 5초 구간 평균. 구간 안에 NG가 하나라도 있으면 그 점은 NG예요 */
    private static List<MetricsResponse.Point> fiveSecondPoints(List<Map<String, Object>> rows, String column) {
        Map<Long, double[]> buckets = new LinkedHashMap<>(); // {합계, 개수, NG 있음, 판정 있음}
        for (Map<String, Object> row : rows) {
            if (!(row.get(column) instanceof Number v)) continue;
            long bucket = Kst.local(row.get("measured_at")).toEpochSecond(Kst.OFFSET) / 5;
            double[] b = buckets.computeIfAbsent(bucket, k -> new double[4]);
            b[0] += v.doubleValue();
            b[1]++;
            Object result = row.get("result");
            if (result != null) b[3] = 1;
            if ("NG".equals(result)) b[2] = 1;
        }
        List<MetricsResponse.Point> points = new ArrayList<>();
        buckets.forEach((bucket, b) -> points.add(new MetricsResponse.Point(
                Instant.ofEpochSecond(bucket * 5).atOffset(Kst.OFFSET), b[0] / b[1],
                b[3] == 0 ? null : b[2] == 1 ? "NG" : "OK")));
        return points;
    }

    /** 알람의 item_name(pressure_total)을 응답 이름(pressureTotal)으로. 고장 신호 알람은 항목이 없어요 */
    private static String itemName(String column) {
        return column == null ? null : ProcessData.camel(column);
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
