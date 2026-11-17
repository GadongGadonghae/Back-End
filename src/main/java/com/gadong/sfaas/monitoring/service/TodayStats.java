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
     * M-15. 설비별 그날 처리 수와 CT. CT는 연속한 두 행 간격의 중앙값이라 정지·시뮬레이션 사이 빈 시간이 끼어도 흔들리지 않아요
     * (예전 (마지막 − 첫 행) ÷ (행 수 − 1)은 멈춘 시간까지 나눠서 커졌어요).
     */
    public List<CycleResponse> cycles(String process, String lineId, LocalDate date) {
        ProcessData.Table t = ProcessData.of(process).orElseThrow(() -> invalid("공정 " + process + "의 수집 데이터가 없습니다"));
        masterService.requireLine(lineId);
        MapSqlParameterSource p = window(date == null ? Kst.now().toLocalDate() : date, lineId);
        List<CycleResponse> out = new ArrayList<>();
        if (!"PRESS".equals(t.process())) {
            String line = "PAINTING".equals(t.process()) ? "x.line_id" : "e.line_id"; // 도장은 공용이라 행에 적힌 line_id
            times("SELECT x.equipment_id AS k, x.measured_at AS t FROM " + t.name() + " x JOIN equipment e ON e.equipment_id = x.equipment_id"
                    + " WHERE x.measured_at >= :from AND x.measured_at < :to" + lineFilter(lineId, line), p)
                    .forEach((id, ts) -> out.add(new CycleResponse(id, ts.size(), medianGapMs(ts))));
            return out;
        }
        // 프레스 1행 = 제품 1개이고 PR1→PR4가 쓸 때마다 measured_at이 그 단 시각으로 바뀌어요(실데이터 확인).
        // 그래서 단마다 자기 칸이 채워진 행 수는 정확하지만, 시각이 남는 건 마지막 단(PR4)뿐이에요.
        // shortcut: PR1~PR3 CT는 null. 단별 시각 컬럼이 생기면 그 시각으로 같은 방식(간격 중앙값)으로 채워요
        for (int s = 0; s < ProcessData.PRESS_STAGES.size(); s++) {
            String col = ProcessData.PRESS_STAGES.get(s);
            String stage = "-PR" + (s + 1);
            boolean last = s == ProcessData.PRESS_STAGES.size() - 1;
            times("SELECT e.line_id AS k, x.measured_at AS t FROM press_data x JOIN equipment e ON e.equipment_id = x.equipment_id"
                    + " WHERE x.measured_at >= :from AND x.measured_at < :to AND x." + col + " IS NOT NULL" + lineFilter(lineId, "e.line_id"), p)
                    .forEach((line, ts) -> out.add(new CycleResponse(line + stage, ts.size(), last ? medianGapMs(ts) : null)));
        }
        out.sort((a, b) -> a.equipmentId().compareTo(b.equipmentId()));
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
