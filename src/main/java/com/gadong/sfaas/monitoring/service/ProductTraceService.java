package com.gadong.sfaas.monitoring.service;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.global.util.Checks;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.global.util.Sql;
import com.gadong.sfaas.master.service.MasterService;
import com.gadong.sfaas.monitoring.dto.ProductDtos.HourlySummaryResponse;
import com.gadong.sfaas.monitoring.dto.ProductDtos.ProcessNgResponse;
import com.gadong.sfaas.monitoring.dto.ProductDtos.ProductDetailResponse;
import com.gadong.sfaas.monitoring.dto.ProductDtos.ProductDetailResponse.Step;
import com.gadong.sfaas.monitoring.dto.ProductDtos.ProductResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제품 단위 추적 (판정 설계 v2): 시간대별 생산(G-01 unit=hour), 제품 목록·이력(P-01·P-02), 공정 불량 제품 목록(P-03).
 * 프레스는 제품 1행에 PR1~PR4가 자기 칸을 채우고, 칸마다 그 라인 {라인}-PRn 기준과 비교해요.
 */
@Service
@Transactional(readOnly = true)
public class ProductTraceService {
    private static final List<String> RESULTS = List.of("OK", "NG", "IN_PROGRESS");
    private static final String NO_BREACH = "기준 이탈 없음 (공정 중단)";
    private static final int[] ZERO = new int[3];

    record Limit(BigDecimal low, BigDecimal high, String label) {
        boolean out(BigDecimal v) {
            return (low != null && v.compareTo(low) < 0) || (high != null && v.compareTo(high) > 0);
        }
    }

    /** 처음 기준을 벗어난 항목 */
    record Breach(String equipmentId, String item, BigDecimal value, Limit limit) {
        String reason() {
            String over = limit.high() != null && value.compareTo(limit.high()) > 0 ? " 상한 초과" : " 하한 미달";
            return equipmentId + " " + (limit.label() == null ? item : limit.label()) + over;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final MasterService masterService;

    public ProductTraceService(NamedParameterJdbcTemplate jdbc, MasterService masterService) {
        this.jdbc = jdbc;
        this.masterService = masterService;
    }

    // ── G-01 unit=hour ──

    /** 시간 구간 × 라인. 데이터 없는 시간도 0으로 채우고, 오늘은 지금 시간까지만 */
    public List<HourlySummaryResponse> hourly(LocalDate from, LocalDate to, String lineId) {
        Checks.period(from, to);
        masterService.requireLine(lineId);
        MapSqlParameterSource p = Sql.period(from, to);
        if (lineId != null) p.addValue("lineId", lineId);
        Map<String, int[]> cell = new HashMap<>(); // "라인|시각" -> {inspected, good, processNg}
        jdbc.query("SELECT line_id, CAST(inspected_at AS DATE) AS d, EXTRACT(HOUR FROM inspected_at) AS h, COUNT(*) AS c,"
                + " SUM(CASE WHEN result = 'OK' THEN 1 ELSE 0 END) AS ok FROM inspection_result"
                + " WHERE inspected_at >= :from AND inspected_at < :to" + lineFilter(lineId, "line_id")
                + " GROUP BY line_id, CAST(inspected_at AS DATE), EXTRACT(HOUR FROM inspected_at)", p, rs -> {
                    int[] v = cell.computeIfAbsent(key(rs), k -> new int[3]);
                    v[0] += rs.getInt("c");
                    v[1] += rs.getInt("ok");
                });
        for (ProcessData.Table t : ProcessData.TABLES) {
            String line = lineColumn(t);
            jdbc.query("SELECT " + line + " AS line_id, CAST(x.measured_at AS DATE) AS d, EXTRACT(HOUR FROM x.measured_at) AS h, COUNT(*) AS c"
                    + " FROM " + t.name() + " x JOIN equipment e ON e.equipment_id = x.equipment_id"
                    + " WHERE x.result = 'NG' AND x.measured_at >= :from AND x.measured_at < :to" + lineFilter(lineId, line)
                    + " GROUP BY " + line + ", CAST(x.measured_at AS DATE), EXTRACT(HOUR FROM x.measured_at)", p,
                    rs -> { cell.computeIfAbsent(key(rs), k -> new int[3])[2] += rs.getInt("c"); });
        }

        List<String> lines = lineId != null ? List.of(lineId)
                : jdbc.queryForList("SELECT line_id FROM line ORDER BY line_id", Map.of(), String.class);
        LocalDateTime now = Kst.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
        LocalDateTime end = Kst.endExclusive(to).isBefore(now) ? Kst.endExclusive(to) : now;
        Map<String, Integer> cumulative = new HashMap<>();
        List<HourlySummaryResponse> out = new ArrayList<>();
        for (LocalDateTime h = Kst.start(from); h.isBefore(end); h = h.plusHours(1)) {
            for (String l : lines) {
                int[] v = cell.getOrDefault(l + "|" + h, ZERO);
                out.add(new HourlySummaryResponse(Kst.of(h), l, v[0], v[1], v[2], cumulative.merge(l, v[1], Integer::sum)));
            }
        }
        return out;
    }

    private static String key(ResultSet rs) throws SQLException {
        return rs.getString("line_id") + "|" + rs.getObject("d", LocalDate.class).atTime(rs.getInt("h"), 0);
    }

    // ── P-01 · P-02 제품 ──

    /** P-01. 투입 시각(slitting_data에 product_id가 처음 생긴 시각) 최신순 */
    public PageResponse<ProductResponse> products(LocalDate from, LocalDate to, String lineId, String result, int page, int size) {
        Checks.period(from, to);
        Checks.page(page, size);
        masterService.requireLine(lineId);
        if (result != null && !RESULTS.contains(result)) throw invalid("result는 OK, NG, IN_PROGRESS입니다");
        MapSqlParameterSource p = Sql.period(from, to);
        StringBuilder inner = new StringBuilder("SELECT s.product_id, MIN(s.measured_at) AS input_at FROM slitting_data s"
                + " JOIN equipment e ON e.equipment_id = s.equipment_id WHERE s.product_id IS NOT NULL");
        Sql.eq(inner, p, "e.line_id", "lineId", lineId);
        inner.append(" GROUP BY s.product_id");
        StringBuilder from_ = new StringBuilder(" FROM (SELECT g.product_id, g.input_at, ").append(finalResultSql())
                .append(" AS final_result FROM (").append(inner).append(") g WHERE g.input_at >= :from AND g.input_at < :to) t WHERE 1 = 1");
        Sql.eq(from_, p, "t.final_result", "result", result);

        long total = jdbc.queryForObject("SELECT COUNT(*)" + from_, p, Long.class);
        List<String> ids = jdbc.queryForList("SELECT t.product_id" + from_ + " ORDER BY t.input_at DESC, t.product_id LIMIT :limit OFFSET :offset",
                Sql.paging(p, page, size), String.class);
        // shortcut: 제품마다 공정 테이블 5개를 따로 읽어요(한 페이지 최대 100건). 느려지면 product_id 인덱스와 한 번에 읽기로 바꿔요
        Map<String, Map<String, Limit>> limits = limits();
        List<ProductResponse> items = ids.stream().map(id -> {
            ProductDetailResponse d = trace(id, limits);
            Step first = d.steps().get(0);
            Step scrapped = d.steps().stream().filter(s -> s.process().equals(d.scrappedProcess()) && s.equipmentId().equals(d.scrappedEquipmentId()))
                    .findFirst().orElse(null);
            Step inspected = d.steps().stream().filter(s -> "INSPECTION".equals(s.process()) && "OK".equals(s.result())).findFirst().orElse(null);
            OffsetDateTime finishedAt = "OK".equals(d.finalResult()) ? (inspected == null ? null : inspected.at())
                    : "NG".equals(d.finalResult()) && scrapped != null ? scrapped.at() : null;
            return new ProductResponse(id, d.lineId(), "SLITTING".equals(first.process()) ? first.at() : null, d.finalResult(),
                    d.scrappedProcess(), d.scrappedEquipmentId(), finishedAt);
        }).toList();
        return new PageResponse<>(items, total, page, size);
    }

    /** final_result 계산: 비전 OK → OK, 공정 NG 또는 비전 NG → NG, 그 외 IN_PROGRESS (P-02 trace와 같은 규칙) */
    private static String finalResultSql() {
        StringBuilder b = new StringBuilder("CASE WHEN EXISTS (SELECT 1 FROM inspection_result i WHERE i.product_id = g.product_id AND i.result = 'OK') THEN 'OK'"
                + " WHEN EXISTS (SELECT 1 FROM inspection_result i WHERE i.product_id = g.product_id AND i.result = 'NG')");
        for (ProcessData.Table t : ProcessData.TABLES) {
            b.append(" OR EXISTS (SELECT 1 FROM ").append(t.name()).append(" x WHERE x.product_id = g.product_id AND x.result = 'NG')");
        }
        return b.append(" THEN 'NG' ELSE 'IN_PROGRESS' END").toString();
    }

    /** P-02 */
    public ProductDetailResponse product(String productId) {
        return trace(productId, limits());
    }

    private ProductDetailResponse trace(String productId, Map<String, Map<String, Limit>> limits) {
        List<Step> steps = new ArrayList<>();
        String lineId = null, scrappedProcess = null, scrappedEquipment = null;
        boolean ng = false, ok = false;
        Map<String, Object> id = Map.of("id", productId);
        for (ProcessData.Table t : ProcessData.TABLES) {
            for (Map<String, Object> row : jdbc.queryForList("SELECT x.*, e.line_id AS eq_line FROM " + t.name()
                    + " x JOIN equipment e ON e.equipment_id = x.equipment_id WHERE x.product_id = :id ORDER BY x.id", id)) {
                OffsetDateTime at = Kst.of(Kst.local(row.get("measured_at")));
                String equipmentId = (String) row.get("equipment_id");
                if (lineId == null) lineId = (String) ("PAINTING".equals(t.process()) ? row.get("line_id") : row.get("eq_line"));
                boolean rowNg = "NG".equals(row.get("result"));
                if ("PRESS".equals(t.process())) {
                    for (int i = 0; i < ProcessData.PRESS_STAGES.size(); i++) { // 값이 없는 이후 단은 빼요
                        String col = ProcessData.PRESS_STAGES.get(i);
                        BigDecimal v = decimal(row.get(col));
                        if (v == null) continue;
                        String stage = row.get("eq_line") + "-PR" + (i + 1);
                        Limit limit = limit(limits, stage, col);
                        steps.add(new Step("PRESS", stage, at, Map.of(ProcessData.camel(col), v), limit != null && limit.out(v) ? "NG" : "OK"));
                    }
                } else {
                    steps.add(new Step(t.process(), equipmentId, at, ProcessData.values(row, t), (String) row.get("result")));
                }
                if (rowNg && scrappedProcess == null) {
                    Breach b = breach(t, row, limits);
                    scrappedProcess = t.process();
                    scrappedEquipment = b != null ? b.equipmentId() : equipmentId; // 프레스는 처음 벗어난 칸의 설비
                }
                ng |= rowNg;
            }
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT equipment_id, line_id, result, inspected_at FROM inspection_result"
                + " WHERE product_id = :id ORDER BY inspection_id", id)) {
            String result = (String) row.get("result");
            steps.add(new Step("INSPECTION", (String) row.get("equipment_id"), Kst.of(Kst.local(row.get("inspected_at"))), Map.of(), result));
            if (lineId == null) lineId = (String) row.get("line_id");
            ok |= "OK".equals(result);
            if ("NG".equals(result)) {
                ng = true;
                if (scrappedProcess == null) {
                    scrappedProcess = "INSPECTION";
                    scrappedEquipment = (String) row.get("equipment_id");
                }
            }
        }
        if (steps.isEmpty()) throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "제품 " + productId + "를 찾을 수 없습니다");
        String finalResult = ok ? "OK" : ng ? "NG" : "IN_PROGRESS";
        if (ok) scrappedProcess = scrappedEquipment = null;
        return new ProductDetailResponse(productId, lineId, finalResult, scrappedProcess, scrappedEquipment, steps);
    }

    // ── P-03 공정 불량 제품 ──

    /** P-03. 4개 공정 테이블의 result = 'NG' 행을 합쳐 최신순 */
    public PageResponse<ProcessNgResponse> processNg(LocalDate from, LocalDate to, String lineId, String process, int page, int size) {
        Checks.period(from, to);
        Checks.page(page, size);
        masterService.requireLine(lineId);
        if (process != null && ProcessData.of(process).isEmpty()) throw invalid("process는 SLITTING, PRESS, WELDING, PAINTING입니다");
        MapSqlParameterSource p = Sql.period(from, to);
        if (lineId != null) p.addValue("lineId", lineId);
        List<String> parts = new ArrayList<>();
        for (ProcessData.Table t : ProcessData.TABLES) {
            if (process != null && !t.process().equalsIgnoreCase(process)) continue;
            String line = lineColumn(t);
            parts.add("SELECT '" + t.process() + "' AS process, x.id, x.product_id, " + line + " AS line_id, x.measured_at FROM " + t.name()
                    + " x JOIN equipment e ON e.equipment_id = x.equipment_id"
                    + " WHERE x.result = 'NG' AND x.measured_at >= :from AND x.measured_at < :to" + lineFilter(lineId, line));
        }
        String union = "(" + String.join(" UNION ALL ", parts) + ") u";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + union, p, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + union
                + " ORDER BY u.measured_at DESC, u.id DESC LIMIT :limit OFFSET :offset", Sql.paging(p, page, size));

        Map<String, Map<String, Limit>> limits = limits();
        List<ProcessNgResponse> items = rows.stream().map(u -> {
            ProcessData.Table t = ProcessData.of((String) u.get("process")).orElseThrow();
            Map<String, Object> row = jdbc.queryForMap("SELECT x.*, e.line_id AS eq_line FROM " + t.name()
                    + " x JOIN equipment e ON e.equipment_id = x.equipment_id WHERE x.id = :id", Map.of("id", u.get("id")));
            Breach b = breach(t, row, limits);
            return new ProcessNgResponse((String) u.get("product_id"), (String) u.get("line_id"), t.process(),
                    b != null ? b.equipmentId() : (String) row.get("equipment_id"), Kst.of(Kst.local(u.get("measured_at"))),
                    b == null ? null : b.item(), b == null ? null : b.value(), b == null ? null : b.limit().low(),
                    b == null ? null : b.limit().high(), b == null ? NO_BREACH : b.reason());
        }).toList();
        return new PageResponse<>(items, total, page, size);
    }

    // ── 공통 ──

    /** 행에서 처음 기준을 벗어난 항목. 프레스는 PR1→PR4 칸 순서로 그 라인 {라인}-PRn 기준, 그 외는 행 설비의 기준 */
    private static Breach breach(ProcessData.Table t, Map<String, Object> row, Map<String, Map<String, Limit>> limits) {
        boolean press = "PRESS".equals(t.process());
        List<String> columns = press ? ProcessData.PRESS_STAGES : t.columns();
        for (int i = 0; i < columns.size(); i++) {
            String col = columns.get(i);
            BigDecimal v = decimal(row.get(col));
            if (v == null) continue;
            String equipmentId = press ? row.get("eq_line") + "-PR" + (i + 1) : (String) row.get("equipment_id");
            Limit limit = limit(limits, equipmentId, col);
            if (limit != null && limit.out(v)) return new Breach(equipmentId, col, v, limit);
        }
        return null;
    }

    /** 요청마다 기준치를 한 번 읽어요 (화면에서 바꾼 기준이 바로 반영) */
    private Map<String, Map<String, Limit>> limits() {
        Map<String, Map<String, Limit>> m = new HashMap<>();
        jdbc.query("SELECT equipment_id, item_name, item_label, lower_limit, upper_limit FROM threshold", Map.of(), rs -> {
            m.computeIfAbsent(rs.getString("equipment_id"), k -> new HashMap<>()).put(rs.getString("item_name"),
                    new Limit(rs.getBigDecimal("lower_limit"), rs.getBigDecimal("upper_limit"), rs.getString("item_label")));
        });
        return m;
    }

    private static Limit limit(Map<String, Map<String, Limit>> limits, String equipmentId, String item) {
        return limits.getOrDefault(equipmentId, Map.of()).get(item);
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    /** 도장은 공용 설비라 행에 적힌 line_id, 그 외는 설비의 라인 */
    private static String lineColumn(ProcessData.Table t) {
        return "PAINTING".equals(t.process()) ? "x.line_id" : "e.line_id";
    }

    private static String lineFilter(String lineId, String column) {
        return lineId == null ? "" : " AND " + column + " = :lineId"; // column은 코드에 적힌 값만 와요
    }
}
