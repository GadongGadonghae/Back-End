package com.gadong.sfaas.monitoring.service;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.master.service.MasterService;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.ProductionCountsResponse;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.WipResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 생산량 집계(M-07, M-13)와 다른 API(U-01, U-03, U-11, G-01)가 같이 쓰는 집계.
 * 정의(명세): 생산 수 = 검사 수 + 공정 불량 수, 최종 양품 = 검사 수 − 비전 불량, 양품률 = 양품 ÷ 생산 수.
 */
@Component
@Transactional(readOnly = true)
public class ProductionStats {

    /** 하루·라인·도장 설비별 집계. paintId는 비전 검사 행과 도장 불량 행에만 있어요 */
    public record Row(LocalDate day, String lineId, String paintId, long inspected, long ngVision, long processNg) {
        public long total() { return inspected + processNg; }
        public long ok() { return inspected - ngVision; }
    }

    /** 공정 순서. COIL은 설비가 없고 coil_receipt로 세요 */
    private static final List<String> FLOW = List.of("COIL", "SLITTING", "PRESS", "WELDING", "PAINTING", "INSPECTION");
    private static final String NG = "CASE WHEN %s.result = 'NG' THEN 1 ELSE 0 END";

    private final NamedParameterJdbcTemplate jdbc;
    private final MasterService masterService;
    private final FlowStats flow;

    public ProductionStats(NamedParameterJdbcTemplate jdbc, MasterService masterService, FlowStats flow) {
        this.jdbc = jdbc;
        this.masterService = masterService;
        this.flow = flow;
    }

    /** [from, to] 일별 집계. lineId가 있으면 그 라인만 */
    public List<Row> daily(LocalDate from, LocalDate to, String lineId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", Kst.start(from)).addValue("to", Kst.endExclusive(to));
        if (lineId != null) p.addValue("lineId", lineId);
        StringBuilder sql = new StringBuilder("SELECT d, line_id, paint_id, SUM(insp) AS insp, SUM(ngv) AS ngv, SUM(png) AS png FROM ("
                + " SELECT CAST(i.inspected_at AS DATE) AS d, i.line_id, i.paint_equipment_id AS paint_id, 1 AS insp, "
                + NG.formatted("i") + " AS ngv, 0 AS png FROM inspection_result i"
                + " WHERE i.inspected_at >= :from AND i.inspected_at < :to" + lineFilter(lineId, "i.line_id"));
        for (ProcessData.Table t : ProcessData.TABLES) {
            boolean painting = "PAINTING".equals(t.process()); // 도장은 공용이라 행에 적힌 line_id를 써요
            String line = painting ? "x.line_id" : "e.line_id";
            sql.append(" UNION ALL SELECT CAST(x.measured_at AS DATE), ").append(line).append(", ")
                    .append(painting ? "x.equipment_id" : "CAST(NULL AS VARCHAR(20))").append(", 0, 0, ").append(NG.formatted("x"))
                    .append(" FROM ").append(t.name()).append(" x JOIN equipment e ON e.equipment_id = x.equipment_id")
                    .append(" WHERE x.measured_at >= :from AND x.measured_at < :to").append(lineFilter(lineId, line));
        }
        sql.append(") t GROUP BY d, line_id, paint_id ORDER BY d");
        return jdbc.query(sql.toString(), p, (rs, n) -> new Row(rs.getObject("d", LocalDate.class), rs.getString("line_id"),
                rs.getString("paint_id"), rs.getLong("insp"), rs.getLong("ngv"), rs.getLong("png")));
    }

    /** 공정별 {행 수, NG 수} (SLITTING·PRESS·WELDING·PAINTING + INSPECTION). lineId가 있으면 그 라인만 */
    public Map<String, long[]> processRows(LocalDate from, LocalDate to, String lineId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", Kst.start(from)).addValue("to", Kst.endExclusive(to));
        if (lineId != null) p.addValue("lineId", lineId);
        String sum = "SELECT COUNT(*), COALESCE(SUM(" + NG + "), 0) FROM ";
        Map<String, long[]> rows = new TreeMap<>();
        for (ProcessData.Table t : ProcessData.TABLES) {
            String line = "PAINTING".equals(t.process()) ? "x.line_id" : "e.line_id";
            rows.put(t.process(), count(sum.formatted("x") + t.name() + " x JOIN equipment e ON e.equipment_id = x.equipment_id"
                    + " WHERE x.measured_at >= :from AND x.measured_at < :to" + lineFilter(lineId, line), p));
        }
        rows.put("INSPECTION", count(sum.formatted("i") + "inspection_result i WHERE i.inspected_at >= :from AND i.inspected_at < :to"
                + lineFilter(lineId, "i.line_id"), p));
        return rows;
    }

    /** M-07. date를 생략하면 오늘. byProcess는 lineId를 지정했을 때만 채워요 */
    public ProductionCountsResponse counts(LocalDate date, String lineId, String groupBy) {
        if (!"line".equals(groupBy) && !"paint".equals(groupBy)) throw invalid("groupBy는 line 또는 paint입니다");
        masterService.requireLine(lineId);
        LocalDate day = date == null ? Kst.now().toLocalDate() : date;
        List<Row> rows = daily(day, day, lineId);
        long inspected = rows.stream().mapToLong(Row::inspected).sum();
        long ngVision = rows.stream().mapToLong(Row::ngVision).sum();
        long processNg = rows.stream().mapToLong(Row::processNg).sum();
        long total = inspected + processNg, ok = inspected - ngVision;

        Map<String, long[]> groups = new TreeMap<>(); // {생산 수, 양품}
        for (Row r : rows) {
            String key = "line".equals(groupBy) ? r.lineId() : r.paintId(); // 도장 앞에서 폐기된 불량은 도장 설비가 없어 paint 묶음에서 빠져요
            if (key == null) continue;
            long[] g = groups.computeIfAbsent(key, k -> new long[2]);
            g[0] += r.total();
            g[1] += r.ok();
        }
        List<ProductionCountsResponse.GroupCount> byGroup = groups.entrySet().stream()
                .map(e -> new ProductionCountsResponse.GroupCount(e.getKey(), (int) e.getValue()[0], (int) e.getValue()[1],
                        (int) (e.getValue()[0] - e.getValue()[1]))).toList();
        List<ProductionCountsResponse.ProcessCount> byProcess = new ArrayList<>();
        if (lineId != null) {
            processRows(day, day, lineId).forEach((process, c) -> {
                if (!"INSPECTION".equals(process)) byProcess.add(new ProductionCountsResponse.ProcessCount(process, (int) c[0], (int) c[1]));
            });
        }
        return new ProductionCountsResponse(day.toString(), (int) total, (int) processNg, (int) inspected, (int) ngVision, (int) ok,
                total == 0 ? 0 : Math.round(ok * 1000.0 / total) / 10.0, byGroup, byProcess);
    }

    /**
     * M-13. 재공품 = 앞 공정 설비에서 나왔는데 다음 공정 설비에 아직 안 들어간 제품 수 (product_flow, 공정 NG 제외).
     * 처리 중인 제품은 재공이 아니에요. 코일입고 → 슬리팅은 입고 제품 수 − 슬리팅에 들어간 수(전체 조회에만)
     */
    public List<WipResponse> wip(String lineId) {
        masterService.requireLine(lineId);
        Map<String, Long> after = new HashMap<>(); // 공정 → 그 공정 뒤 재공
        for (FlowStats.Waiting w : flow.waiting()) {
            if (lineId != null && !lineId.equals(w.lineId())) continue;
            if ("PRESS".equals(w.process()) && w.seq() != ProcessData.PRESS_STAGES.size()) continue; // 프레스 단 사이 재공은 M-13에 안 나와요
            after.merge(w.process(), w.qty(), Long::sum);
        }
        List<WipResponse> wip = new ArrayList<>();
        if (lineId == null) wip.add(new WipResponse("COIL", "SLITTING", (int) flow.coilWaiting()));
        for (int i = 1; i < FLOW.size() - 1; i++) {
            wip.add(new WipResponse(FLOW.get(i), FLOW.get(i + 1), after.getOrDefault(FLOW.get(i), 0L).intValue()));
        }
        return wip;
    }

    /**
     * M-13 paintId: 도장 설비 하나의 앞·뒤 재공 (판정 설계 v2 2절).
     * 앞 = 지금 이 도장으로 가는 라인들의 용접 뒤 재공 합, 뒤 = 이 도장에서 나와 검사를 기다리는 제품
     */
    public List<WipResponse> paintWip(String paintId) {
        if (!"PAINTING".equals(masterService.equipment(paintId).getProcessId())) throw invalid(paintId + "는 도장 설비가 아닙니다");
        List<String> lines = jdbc.queryForList("SELECT line_id FROM line WHERE current_paint_id = :paintId", Map.of("paintId", paintId), String.class);
        long before = 0, behind = 0;
        for (FlowStats.Waiting w : flow.waiting()) {
            if ("WELDING".equals(w.process()) && lines.contains(w.lineId())) before += w.qty();
            if (paintId.equals(w.equipmentId())) behind += w.qty();
        }
        return List.of(new WipResponse("WELDING", "PAINTING", (int) before), new WipResponse("PAINTING", "INSPECTION", (int) behind));
    }

    private static String lineFilter(String lineId, String column) {
        return lineId == null ? "" : " AND " + column + " = :lineId"; // column은 코드에 적힌 값만 와요
    }

    private long[] count(String sql, MapSqlParameterSource p) {
        return jdbc.queryForObject(sql, p, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)});
    }
}
