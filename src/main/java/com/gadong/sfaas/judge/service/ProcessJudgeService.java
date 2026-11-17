package com.gadong.sfaas.judge.service;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.ProductionCountsResponse;
import com.gadong.sfaas.monitoring.service.ProductionStats;
import com.gadong.sfaas.push.PushService;
import com.gadong.sfaas.push.dto.PushDtos.ProductionMessage;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** 판정 설계 1절: 공정 판정(result)과 W-03 푸시. 비전(inspection_result)은 Node-RED 값을 그대로 쓰고 판정하지 않아요. */
@Service
public class ProcessJudgeService {
    private static final List<String> TABLES = List.of("slitting_data", "press_data", "welding_data", "painting_data");
    private static final int BATCH = 500;

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
        int judged = 0;
        for (String table : TABLES) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM " + table + " WHERE result IS NULL ORDER BY id LIMIT " + BATCH, Map.of());
            if (rows.isEmpty()) continue;
            MapSqlParameterSource[] updates = rows.stream()
                    .map(r -> new MapSqlParameterSource().addValue("id", r.get("id")).addValue("result", judgeRow(table, r)))
                    .toArray(MapSqlParameterSource[]::new);
            jdbc.batchUpdate("UPDATE " + table + " SET result = :result WHERE id = :id", updates);
            judged += rows.size();
        }

        ProductionMessage.Last last = newestInspection();
        if (judged > 0 || last != null) pushService.pushProduction(new ProductionMessage(last, today()));
    }

    /** 임시 규칙: 전부 OK. TODO(판정 설계 1절): threshold 상·하한 비교, NG면 알람 저장 */
    String judgeRow(String table, Map<String, Object> row) {
        return "OK";
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
