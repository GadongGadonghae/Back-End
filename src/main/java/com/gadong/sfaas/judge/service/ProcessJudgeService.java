package com.gadong.sfaas.judge.service;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.ProductionCountsResponse;
import com.gadong.sfaas.monitoring.service.ProductionStats;
import com.gadong.sfaas.push.PushService;
import com.gadong.sfaas.push.dto.PushDtos.ProductionMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 판정 설계 v2 1절: 공정 판정(result)과 W-03 푸시. 비전(inspection_result)은 Node-RED 값을 그대로 쓰고 판정하지 않아요.
 * 측정값이 그 설비 threshold(item_name = 컬럼명)의 하한·상한을 벗어나면 NG예요. 기준이 없는 항목은 비교하지 않아요.
 */
@Service
public class ProcessJudgeService {
    private static final List<String> TABLES = List.of("slitting_data", "press_data", "welding_data", "painting_data");
    /** 프레스 1행 = 제품 1개. PR1→PR4가 차례로 한 칸씩 채워요 (설계 v2 1-1) */
    private static final List<String> PRESS_STAGES = List.of("pressure_left", "pressure_right", "pressure_diff", "press_time");
    private static final int BATCH = 500;

    private record Limit(BigDecimal low, BigDecimal high) {
        boolean out(BigDecimal v) {
            return (low != null && v.compareTo(low) < 0) || (high != null && v.compareTo(high) > 0);
        }
    }

    /** 한 번 판정할 때 읽은 기준치: 설비별 {항목 → 기준}, 프레스는 라인별로 묶음(PR1~PR4가 한 행을 같이 채워서) */
    private record Limits(Map<String, Map<String, Limit>> byEquipment, Map<String, String> lineOf,
                          Map<String, Map<String, Limit>> pressByLine) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductionStats productionStats;
    private final PushService pushService;
    // shortcut: 메모리 커서라 서버를 다시 켜면 그 사이 검사 행은 푸시하지 않아요(화면은 M-07로 다시 채움). 서버를 여러 대 띄우면 DB에 저장하도록 바꿔요
    private Long lastInspectionId;

    public ProcessJudgeService(NamedParameterJdbcTemplate jdbc, ProductionStats productionStats, PushService pushService) {
        this.jdbc = jdbc;
        this.productionStats = productionStats;
        this.pushService = pushService;
    }

    /** result IS NULL 행을 판정해 저장하고, 판정했거나 새 검사 행이 있으면 W-03을 한 번 보내요 */
    public void judge() {
        Limits limits = loadLimits(); // 화면(M-11)에서 바꾼 기준이 다음 초부터 바로 쓰이게 매번 읽어요 (56행)
        int judged = 0;
        for (String table : TABLES) {
            // 값을 기다리는 프레스 행(라인마다 몇 개)도 여기 남아요. 버려진 행이 500개 넘게 쌓이면 뒤 행이 밀리니 그때는 id 커서로 바꿔요
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM " + table + " WHERE result IS NULL ORDER BY id LIMIT " + BATCH, Map.of());
            List<MapSqlParameterSource> updates = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String result = judgeRow(table, row, limits);
                if (result != null) updates.add(new MapSqlParameterSource().addValue("id", row.get("id")).addValue("result", result));
            }
            if (updates.isEmpty()) continue;
            // result IS NULL 조건: 그 사이 Node-RED가 바꾼 행을 덮어쓰지 않게
            jdbc.batchUpdate("UPDATE " + table + " SET result = :result WHERE id = :id AND result IS NULL",
                    updates.toArray(MapSqlParameterSource[]::new));
            judged += updates.size();
        }

        ProductionMessage.Last last = newestInspection();
        if (judged > 0 || last != null) pushService.pushProduction(new ProductionMessage(last, today()));
    }

    /** OK / NG, 아직 판정할 수 없으면(프레스 값이 덜 참) null. TODO(설계 v2 1절 4): NG면 알람 저장·W-02 */
    String judgeRow(String table, Map<String, Object> row, Limits limits) {
        String equipmentId = (String) row.get("equipment_id");
        if ("press_data".equals(table)) {
            Map<String, Limit> line = limits.pressByLine().getOrDefault(limits.lineOf().get(equipmentId), Map.of());
            boolean complete = true;
            for (String item : PRESS_STAGES) { // PR1부터 차례로, 벗어난 칸이 하나라도 있으면 그 자리에서 NG
                BigDecimal v = decimal(row.get(item));
                if (v == null) { complete = false; continue; }
                Limit limit = line.get(item);
                if (limit != null && limit.out(v)) return "NG";
            }
            return complete ? "OK" : null;
        }
        for (var e : limits.byEquipment().getOrDefault(equipmentId, Map.of()).entrySet()) {
            BigDecimal v = decimal(row.get(e.getKey()));
            if (v != null && e.getValue().out(v)) return "NG";
        }
        return "OK";
    }

    private Limits loadLimits() {
        Map<String, Map<String, Limit>> byEquipment = new HashMap<>();
        Map<String, Map<String, Limit>> pressByLine = new HashMap<>();
        jdbc.query("SELECT t.equipment_id, e.line_id, e.process_id, t.item_name, t.lower_limit, t.upper_limit"
                + " FROM threshold t JOIN equipment e ON e.equipment_id = t.equipment_id", Map.of(), rs -> {
            Limit limit = new Limit(rs.getBigDecimal("lower_limit"), rs.getBigDecimal("upper_limit"));
            String item = rs.getString("item_name");
            byEquipment.computeIfAbsent(rs.getString("equipment_id"), k -> new HashMap<>()).put(item, limit);
            if ("PRESS".equals(rs.getString("process_id"))) {
                pressByLine.computeIfAbsent(rs.getString("line_id"), k -> new HashMap<>()).put(item, limit);
            }
        });
        Map<String, String> lineOf = new HashMap<>();
        jdbc.query("SELECT equipment_id, line_id FROM equipment WHERE line_id IS NOT NULL", Map.of(),
                rs -> { lineOf.put(rs.getString(1), rs.getString(2)); });
        return new Limits(byEquipment, lineOf, pressByLine);
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    /** 지난 호출 뒤 새로 들어온 검사 행 중 가장 최근 것. 첫 호출은 기준점만 잡아요(과거 행을 몰아서 보내지 않게) */
    private ProductionMessage.Last newestInspection() {
        Long max = jdbc.queryForObject("SELECT MAX(inspection_id) FROM inspection_result", Map.of(), Long.class);
        if (lastInspectionId == null || max == null || max <= lastInspectionId) {
            if (lastInspectionId == null) lastInspectionId = max == null ? 0L : max;
            return null;
        }
        lastInspectionId = max;
        return jdbc.queryForObject("SELECT line_id, paint_equipment_id, result FROM inspection_result WHERE inspection_id = :id",
                Map.of("id", max), (rs, n) -> new ProductionMessage.Last(rs.getString("line_id"),
                        rs.getString("paint_equipment_id"), rs.getString("result")));
    }

    /** M-07과 같은 계산 */
    private ProductionMessage.Today today() {
        ProductionCountsResponse c = productionStats.counts(Kst.now().toLocalDate(), null, "line");
        return new ProductionMessage.Today(c.total(), c.processNg(), c.inspected(), c.ngVision(), c.ok(), c.yieldRate());
    }
}
