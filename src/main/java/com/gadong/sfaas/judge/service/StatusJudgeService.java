package com.gadong.sfaas.judge.service;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.EquipmentStatusResponse;
import com.gadong.sfaas.monitoring.service.MonitoringService;
import com.gadong.sfaas.push.PushService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판정 설계 3·4절: 설비 상태 판정 D > DC > S > I > R, 1초마다 39대.
 * 상태가 바뀐 설비만 이력을 저장하고 비가동 이벤트(I·D 새로, S는 S-02 이벤트 연결)를 이어 W-01을 보내요.
 */
@Service
public class StatusJudgeService {
    static final long DC_SECONDS = 10;
    private static final List<String> PRESS_STAGES = List.of("pressure_left", "pressure_right", "pressure_diff", "press_time");

    record Decision(String status, String code, Long plannedEventId) {}

    record Equipment(String id, String process, String lineId, int seq) {}

    /** 한 번 판정할 때 읽은 값 */
    record Snapshot(LocalDateTime now, Map<String, LocalDateTime> lastSeen, Set<String> fault, Map<String, Long> planned,
                    Map<String, Long> upstream) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final MonitoringService monitoringService;
    private final PushService pushService;

    public StatusJudgeService(NamedParameterJdbcTemplate jdbc, MonitoringService monitoringService, PushService pushService) {
        this.jdbc = jdbc;
        this.monitoringService = monitoringService;
        this.pushService = pushService;
    }

    @Transactional
    public void judge() {
        Map<String, String> openStatus = new HashMap<>();
        Map<String, Long> openDowntime = new HashMap<>();
        jdbc.query("SELECT equipment_id, status, downtime_id FROM equipment_status_history WHERE ended_at IS NULL", Map.of(), rs -> {
            openStatus.put(rs.getString("equipment_id"), rs.getString("status").trim());
            long d = rs.getLong("downtime_id");
            if (!rs.wasNull()) openDowntime.put(rs.getString("equipment_id"), d);
        });

        List<Equipment> equipment = jdbc.query("SELECT equipment_id, process_id, line_id, seq_no FROM equipment WHERE is_active = TRUE",
                Map.of(), (rs, n) -> new Equipment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
        Snapshot s = snapshot(Kst.now(), equipment);

        Set<String> changed = new HashSet<>();
        for (Equipment e : equipment) {
            Decision d = decide(e, s);
            String before = openStatus.get(e.id());
            if (d.status().equals(before)) continue;
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", e.id()).addValue("status", d.status()).addValue("now", s.now());
            // 4절: 이전 I·D 이벤트는 닫아요. S 이벤트는 S-03(계획정지 종료)이 닫아요
            if (("I".equals(before) || "D".equals(before)) && openDowntime.containsKey(e.id())) {
                jdbc.update("UPDATE downtime_event SET ended_at = :now WHERE downtime_id = :d AND ended_at IS NULL",
                        new MapSqlParameterSource("now", s.now()).addValue("d", openDowntime.get(e.id())));
            }
            Long downtimeId = switch (d.status()) {
                case "I", "D" -> openEvent(e.id(), d, s.now());
                case "S" -> d.plannedEventId();
                default -> null; // R·DC는 이벤트 없음
            };
            jdbc.update("UPDATE equipment_status_history SET ended_at = :now WHERE equipment_id = :id AND ended_at IS NULL", p);
            jdbc.update("INSERT INTO equipment_status_history(equipment_id, status, started_at, downtime_id) VALUES (:id, :status, :now, :d)",
                    p.addValue("d", downtimeId));
            changed.add(e.id());
        }
        if (changed.isEmpty()) return;

        // M-01과 같은 값으로 보내요 (명세: 상태 공통 필드는 어느 API로 봐도 같음)
        List<EquipmentStatusResponse> statuses = monitoringService.status(null);
        statuses.stream().filter(st -> changed.contains(st.equipmentId())).forEach(pushService::pushStatus);
    }

    /** 3절 우선순위: D 고장 신호 > DC 신호 10초 끊김(신호 행 없음 포함) > S 진행 중 계획정지 > I 앞 재공 없음 > R */
    Decision decide(Equipment e, Snapshot s) {
        if (s.fault().contains(e.id())) return new Decision("D", "UP_BREAKDOWN", null);
        LocalDateTime seen = s.lastSeen().get(e.id());
        if (seen == null || seen.isBefore(s.now().minusSeconds(DC_SECONDS))) return new Decision("DC", null, null);
        Long planned = s.planned().get(e.id()); // 공용 설비(도장·검사)는 S-02가 이벤트를 만들지 않아서 S가 안 나와요
        if (planned != null) return new Decision("S", null, planned);
        if (s.upstream().getOrDefault(e.id(), 0L) <= 0) return new Decision("I", "UP_SHORTAGE", null);
        return new Decision("R", null, null);
    }

    private Long openEvent(String equipmentId, Decision d, LocalDateTime now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO downtime_event(equipment_id, status, code, started_at) VALUES (:id, :status, :code, :now)",
                new MapSqlParameterSource("id", equipmentId).addValue("status", d.status()).addValue("code", d.code()).addValue("now", now),
                key, new String[] {"downtime_id"});
        return key.getKey().longValue();
    }

    private Snapshot snapshot(LocalDateTime now, List<Equipment> equipment) {
        Map<String, LocalDateTime> lastSeen = new HashMap<>();
        Set<String> fault = new HashSet<>();
        jdbc.query("SELECT equipment_id, last_seen_at, fault FROM equipment_signal", Map.of(), rs -> {
            lastSeen.put(rs.getString(1), Kst.local(rs.getObject(2)));
            if (rs.getInt(3) == 1) fault.add(rs.getString(1));
        });
        Map<String, Long> planned = new HashMap<>();
        jdbc.query("SELECT equipment_id, downtime_id FROM downtime_event WHERE status = 'S' AND ended_at IS NULL AND started_at <= :now",
                Map.of("now", now), rs -> { planned.put(rs.getString(1), rs.getLong(2)); });
        return new Snapshot(now, lastSeen, fault, planned, upstream(equipment));
    }

    /**
     * 설비마다 "앞에서 넘어와 아직 이 설비가 끝내지 못한 제품 수" = 앞 설비 OK 수 − 이 설비가 끝낸 수.
     * 이 설비 안에서 처리 중인 제품과 앞에서 기다리는 재공을 합친 수라서 0이면 처리할 게 없는 대기(I)예요.
     * (화면 재공 M-13은 여기서 처리 중 1개를 빼고 보여 줘요)
     * shortcut: 행 수로 세서 막 시작한 제품·멈춘 제품을 구분 못 해요, DB에 설비별 started_at/ended_at이 생기면 그 컬럼으로 바꿔요
     */
    Map<String, Long> upstream(List<Equipment> equipment) {
        Map<String, Long> slitOk = byLine("SELECT e.line_id, SUM(CASE WHEN x.result = 'OK' THEN 1 ELSE 0 END) FROM slitting_data x"
                + " JOIN equipment e ON e.equipment_id = x.equipment_id GROUP BY e.line_id");
        Map<String, Long> pressRows = byLine("SELECT e.line_id, COUNT(*) FROM press_data x JOIN equipment e ON e.equipment_id = x.equipment_id GROUP BY e.line_id");
        Map<String, Long> pressOk = byLine("SELECT e.line_id, SUM(CASE WHEN x.result = 'OK' THEN 1 ELSE 0 END) FROM press_data x"
                + " JOIN equipment e ON e.equipment_id = x.equipment_id GROUP BY e.line_id");
        Map<String, Long> weldRows = byLine("SELECT e.line_id, COUNT(*) FROM welding_data x JOIN equipment e ON e.equipment_id = x.equipment_id GROUP BY e.line_id");
        Map<String, Long> weldOk = byLine("SELECT e.line_id, SUM(CASE WHEN x.result = 'OK' THEN 1 ELSE 0 END) FROM welding_data x"
                + " JOIN equipment e ON e.equipment_id = x.equipment_id GROUP BY e.line_id");
        Map<String, Long> paintRowsByLine = byLine("SELECT line_id, COUNT(*) FROM painting_data WHERE line_id IS NOT NULL GROUP BY line_id");
        Map<String, Long> routeCount = new HashMap<>(); // 도장 설비 → 지금 그 도장으로 가는 라인들의 용접→도장 대기 합
        jdbc.query("SELECT line_id, current_paint_id FROM line", Map.of(), rs -> {
            String line = rs.getString(1);
            long waiting = Math.max(0, weldOk.getOrDefault(line, 0L) - paintRowsByLine.getOrDefault(line, 0L));
            routeCount.merge(rs.getString(2), waiting, Long::sum);
        });
        long paintOk = count("SELECT COUNT(*) FROM painting_data WHERE result = 'OK'");
        long inspected = count("SELECT COUNT(*) FROM inspection_result");
        long coil = count("SELECT COALESCE(SUM(product_qty), 0) FROM coil_receipt") - count("SELECT COUNT(*) FROM slitting_data");
        // 프레스 PR2~PR4: 앞 단 칸은 찼고 이 단 칸은 비었고 NG로 폐기되지 않은 행
        Map<Integer, Map<String, Long>> stageWaiting = new HashMap<>();
        for (int n = 2; n <= PRESS_STAGES.size(); n++) {
            stageWaiting.put(n, byLine("SELECT e.line_id, COUNT(*) FROM press_data x JOIN equipment e ON e.equipment_id = x.equipment_id"
                    + " WHERE x." + PRESS_STAGES.get(n - 2) + " IS NOT NULL AND x." + PRESS_STAGES.get(n - 1) + " IS NULL"
                    + " AND (x.result IS NULL OR x.result <> 'NG') GROUP BY e.line_id"));
        }

        Map<String, Long> out = new HashMap<>();
        for (Equipment e : equipment) {
            String l = e.lineId();
            long v = switch (e.process()) {
                case "SLITTING" -> coil; // 코일은 라인 구분이 없어 공장 전체 재고로 봐요
                case "PRESS" -> e.seq() <= 1 ? slitOk.getOrDefault(l, 0L) - pressRows.getOrDefault(l, 0L)
                        : stageWaiting.getOrDefault(e.seq(), Map.of()).getOrDefault(l, 0L);
                case "WELDING" -> pressOk.getOrDefault(l, 0L) - weldRows.getOrDefault(l, 0L);
                case "PAINTING" -> routeCount.getOrDefault(e.id(), 0L);
                case "INSPECTION" -> paintOk - inspected;
                default -> 1L; // 모르는 공정은 대기로 보지 않아요
            };
            out.put(e.id(), v);
        }
        return out;
    }

    private Map<String, Long> byLine(String sql) {
        Map<String, Long> m = new HashMap<>();
        jdbc.query(sql, Map.of(), rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Map.of(), Long.class);
        return v == null ? 0 : v;
    }
}
