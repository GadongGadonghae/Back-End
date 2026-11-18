package com.gadong.sfaas.monitoring.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * product_flow(v0.3.9 공정 이동 기록)로 세는 재공·처리 중. M-13 재공과 상태 판정(I 대기)이 같이 써요.
 * 재공 = 설비에서 나왔는데(ended_at) 다음 설비에 아직 안 들어간 제품, 공정 판정 NG(폐기)는 빼요.
 */
@Component
@Transactional(readOnly = true)
public class FlowStats {

    /** 설비 equipmentId에서 나와 다음 설비를 기다리는 라인 lineId 제품 수. seq는 프레스 단 번호(PR1~PR4) */
    public record Waiting(String equipmentId, String process, int seq, String lineId, long qty) {}

    // 제품의 마지막 이동 기록(flow_id가 가장 큰 줄)이 끝났으면 그 설비 뒤에서 기다리는 중이에요
    private static final String WAITING = """
            SELECT f.equipment_id, e.process_id, e.seq_no, f.line_id, COUNT(*) AS qty
            FROM product_flow f JOIN equipment e ON e.equipment_id = f.equipment_id
            WHERE f.ended_at IS NOT NULL AND e.process_id <> 'INSPECTION'
              AND NOT EXISTS (SELECT 1 FROM product_flow g WHERE g.product_id = f.product_id AND g.flow_id > f.flow_id)
              AND NOT EXISTS (SELECT 1 FROM slitting_data x WHERE x.product_id = f.product_id AND x.result = 'NG')
              AND NOT EXISTS (SELECT 1 FROM press_data x WHERE x.product_id = f.product_id AND x.result = 'NG')
              AND NOT EXISTS (SELECT 1 FROM welding_data x WHERE x.product_id = f.product_id AND x.result = 'NG')
              AND NOT EXISTS (SELECT 1 FROM painting_data x WHERE x.product_id = f.product_id AND x.result = 'NG')
            GROUP BY f.equipment_id, e.process_id, e.seq_no, f.line_id""";

    private final NamedParameterJdbcTemplate jdbc;

    public FlowStats(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // shortcut: 매번 전체 이동 기록을 훑어요(하루 수천 줄 기준). 기록이 수십만 줄로 쌓이면 기간을 자르거나 집계 테이블로 바꿔요
    public List<Waiting> waiting() {
        return jdbc.query(WAITING, Map.of(), (rs, n) -> new Waiting(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getLong(5)));
    }

    /** 설비별 처리 중인 제품 수 (들어갔고 아직 안 나옴) */
    public Map<String, Long> inProcess() {
        Map<String, Long> m = new HashMap<>();
        jdbc.query("SELECT equipment_id, COUNT(*) FROM product_flow WHERE started_at IS NOT NULL AND ended_at IS NULL GROUP BY equipment_id",
                Map.of(), rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    /** 코일입고 → 슬리팅 재공: 입고한 제품 수 − 슬리팅에 들어간 제품 수. 코일은 라인 구분이 없어요 */
    public long coilWaiting() {
        Long received = jdbc.queryForObject("SELECT COALESCE(SUM(product_qty), 0) FROM coil_receipt", Map.of(), Long.class);
        Long entered = jdbc.queryForObject("SELECT COUNT(*) FROM product_flow f JOIN equipment e ON e.equipment_id = f.equipment_id"
                + " WHERE e.process_id = 'SLITTING'", Map.of(), Long.class);
        return Math.max(0, received - entered);
    }
}
