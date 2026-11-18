package com.gadong.sfaas.monitoring.service;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.master.service.MasterService;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.CycleResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 하루 안의 세부 집계: 설비별 사이클 수·평균 CT(M-15). 시간대별 생산은 G-01 unit=hour */
@Component
@Transactional(readOnly = true)
public class TodayStats {
    private final NamedParameterJdbcTemplate jdbc;
    private final MasterService masterService;

    public TodayStats(NamedParameterJdbcTemplate jdbc, MasterService masterService) {
        this.jdbc = jdbc;
        this.masterService = masterService;
    }

    /**
     * M-15. 설비별 그날 처리 수(나온 제품)와 CT. CT = 같은 설비에 제품이 들어간 시각(product_flow.started_at) 간격의 중앙값이라
     * 정지·시뮬레이션 사이 빈 시간이 끼어도 흔들리지 않아요. 프레스는 PR1~PR4 설비마다 나와요.
     */
    public List<CycleResponse> cycles(String process, String lineId, LocalDate date) {
        ProcessData.Table t = ProcessData.of(process).orElseThrow(() -> invalid("공정 " + process + "의 수집 데이터가 없습니다"));
        masterService.requireLine(lineId);
        MapSqlParameterSource p = window(date == null ? Kst.now().toLocalDate() : date, lineId).addValue("process", t.process());
        String from = " FROM product_flow f JOIN equipment e ON e.equipment_id = f.equipment_id WHERE e.process_id = :process"
                + lineFilter(lineId, "f.line_id"); // 공용 도장은 제품이 온 라인
        Map<String, List<LocalDateTime>> started = times("SELECT f.equipment_id AS k, f.started_at AS t" + from
                + " AND f.started_at >= :from AND f.started_at < :to", p);
        Map<String, Long> done = new TreeMap<>();
        jdbc.query("SELECT f.equipment_id, COUNT(*)" + from + " AND f.ended_at >= :from AND f.ended_at < :to GROUP BY f.equipment_id", p,
                rs -> { done.put(rs.getString(1), rs.getLong(2)); });
        TreeMap<String, Boolean> ids = new TreeMap<>();
        started.keySet().forEach(k -> ids.put(k, true));
        done.keySet().forEach(k -> ids.put(k, true));
        List<CycleResponse> out = new ArrayList<>();
        for (String id : ids.keySet()) {
            out.add(new CycleResponse(id, done.getOrDefault(id, 0L).intValue(), medianGapMs(started.getOrDefault(id, List.of()))));
        }
        return out;
    }

    /** k별 시각 목록 (시각순) */
    private Map<String, List<LocalDateTime>> times(String sql, MapSqlParameterSource p) {
        Map<String, List<LocalDateTime>> m = new TreeMap<>();
        jdbc.query(sql + " ORDER BY k, t", p, rs -> { m.computeIfAbsent(rs.getString("k"), x -> new ArrayList<>()).add(Kst.local(rs.getObject("t"))); });
        return m;
    }

    /** 연속한 두 시각 간격의 중앙값(ms). 시각이 2개보다 적으면 null */
    static Long medianGapMs(List<LocalDateTime> ts) {
        if (ts.size() < 2) return null;
        long[] gaps = new long[ts.size() - 1];
        for (int i = 1; i < ts.size(); i++) gaps[i - 1] = Duration.between(ts.get(i - 1), ts.get(i)).toMillis();
        java.util.Arrays.sort(gaps);
        int n = gaps.length;
        return n % 2 == 1 ? gaps[n / 2] : (gaps[n / 2 - 1] + gaps[n / 2]) / 2;
    }

    private static MapSqlParameterSource window(LocalDate day, String lineId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", Kst.start(day)).addValue("to", Kst.endExclusive(day));
        if (lineId != null) p.addValue("lineId", lineId);
        return p;
    }

    private static String lineFilter(String lineId, String column) {
        return lineId == null ? "" : " AND " + column + " = :lineId"; // column은 코드에 적힌 값만 와요
    }
}
