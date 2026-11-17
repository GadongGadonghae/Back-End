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

    /** M-15. 설비별 그날 처리 행 수와 평균 CT(첫 행~마지막 행 간격 ÷ (행 수 − 1)) */
    public List<CycleResponse> cycles(String process, String lineId, LocalDate date) {
        ProcessData.Table t = ProcessData.of(process).orElseThrow(() -> invalid("공정 " + process + "의 수집 데이터가 없습니다"));
        masterService.requireLine(lineId);
        MapSqlParameterSource p = window(date == null ? Kst.now().toLocalDate() : date, lineId);
        if (!"PRESS".equals(t.process())) {
            String line = "PAINTING".equals(t.process()) ? "x.line_id" : "e.line_id"; // 도장은 공용이라 행에 적힌 line_id
            return jdbc.query("SELECT x.equipment_id AS id, COUNT(*) AS c, MIN(x.measured_at) AS a, MAX(x.measured_at) AS b"
                    + " FROM " + t.name() + " x JOIN equipment e ON e.equipment_id = x.equipment_id"
                    + " WHERE x.measured_at >= :from AND x.measured_at < :to" + lineFilter(lineId, line)
                    + " GROUP BY x.equipment_id ORDER BY x.equipment_id", p, (rs, n) -> cycle(rs.getString("id"), rs.getLong("c"), rs.getObject("a"), rs.getObject("b")));
        }
        // 프레스는 행이 제품 단위라 단(PR1~PR4)마다 자기 칸이 채워진 행 수를 세요.
        // shortcut: 행에 단별 시각이 없어 CT는 라인의 제품 간격이라 네 단이 같아요(가장 느린 단은 못 가려요). 단별 시각 컬럼이 생기면 그걸로 나눠요
        List<CycleResponse> out = new ArrayList<>();
        for (int s = 0; s < ProcessData.PRESS_STAGES.size(); s++) {
            String col = ProcessData.PRESS_STAGES.get(s);
            String stage = "-PR" + (s + 1);
            out.addAll(jdbc.query("SELECT e.line_id AS line, COUNT(*) AS c, MIN(x.measured_at) AS a, MAX(x.measured_at) AS b"
                    + " FROM press_data x JOIN equipment e ON e.equipment_id = x.equipment_id"
                    + " WHERE x.measured_at >= :from AND x.measured_at < :to AND x." + col + " IS NOT NULL" + lineFilter(lineId, "e.line_id")
                    + " GROUP BY e.line_id", p, (rs, n) -> cycle(rs.getString("line") + stage, rs.getLong("c"), rs.getObject("a"), rs.getObject("b"))));
        }
        out.sort((a, b) -> a.equipmentId().compareTo(b.equipmentId()));
        return out;
    }

    private static CycleResponse cycle(String equipmentId, long count, Object first, Object last) {
        Long avg = count > 1 ? Duration.between(Kst.local(first), Kst.local(last)).toMillis() / (count - 1) : null;
        return new CycleResponse(equipmentId, count, avg);
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
