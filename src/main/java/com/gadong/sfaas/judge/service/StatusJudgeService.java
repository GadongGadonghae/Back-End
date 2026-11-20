package com.gadong.sfaas.judge.service;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.EquipmentStatusResponse;
import com.gadong.sfaas.monitoring.service.FlowStats;
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
    private static final List<String> PRESS_STAGES = List.of("pressure_left", "pressure_right", "pressure_diff", "press_time"); // 단 수만 써요

    record Decision(String status, String code, Long plannedEventId) {}

    record Equipment(String id, String process, String lineId, int seq) {}

    /** 한 번 판정할 때 읽은 값 */
    record Snapshot(LocalDateTime now, Map<String, LocalDateTime> lastSeen, Set<String> fault, Map<String, Long> planned,
                    Map<String, Long> upstream, Set<String> startedToday, Set<String> holding) {}

    /** 프레스 1~3단이 끝낸 제품을 이만큼 넘게 들고 있으면 "다음 단을 기다림"으로 봐요 (끝·시작 메시지 사이 순간은 빼려고) */
    static final long HOLD_SECONDS = 2;

    private final NamedParameterJdbcTemplate jdbc;
    private final MonitoringService monitoringService;
    private final PushService pushService;
    private final FlowStats flow;

    public StatusJudgeService(NamedParameterJdbcTemplate jdbc, MonitoringService monitoringService, PushService pushService, FlowStats flow) {
        this.jdbc = jdbc;
        this.monitoringService = monitoringService;
        this.pushService = pushService;
        this.flow = flow;
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
                case "I", "D" -> d.code() == null ? null : openEvent(e.id(), d, s.now()); // 첫 제품 전 대기는 이벤트 없음
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

    /**
     * 3절 우선순위: D 고장 신호 > DC 신호 10초 끊김(신호 행 없음 포함) > S 진행 중 계획정지 > I 대기 > R.
     * I는 두 가지: 끝낸 제품을 들고 다음 단을 기다림(UP_BLOCKED, 프레스 1~3단, 11/20) · 앞 재공 없음(UP_SHORTAGE)
     */
    Decision decide(Equipment e, Snapshot s) {
        if (s.fault().contains(e.id())) return new Decision("D", "UP_BREAKDOWN", null);
        LocalDateTime seen = s.lastSeen().get(e.id());
        if (seen == null || seen.isBefore(s.now().minusSeconds(DC_SECONDS))) return new Decision("DC", null, null);
        Long planned = s.planned().get(e.id()); // 공용 설비(도장·검사)는 S-02가 이벤트를 만들지 않아서 S가 안 나와요
        if (planned != null) return new Decision("S", null, planned);
        // 프레스 1~3단은 단 사이에 쌓을 자리가 없어 뒤 단이 받아 갈 때까지 끝낸 제품을 들고 서 있어요 → 후공정 정지 대기
        if (s.holding().contains(e.id())) return new Decision("I", "UP_BLOCKED", null);
        if (s.upstream().getOrDefault(e.id(), 0L) <= 0) {
            // 오늘 첫 제품이 들어오기 전 대기는 가동 준비라 비가동이 아니에요 (상태는 I로 보여 주되 사유·비가동 이벤트 없음, 11/18)
            return new Decision("I", s.startedToday().contains(e.id()) ? "UP_SHORTAGE" : null, null);
        }
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
        // 오늘 제품이 한 번이라도 들어온 설비 (첫 제품 전 대기를 비가동에서 빼려고)
        Set<String> startedToday = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT equipment_id FROM product_flow WHERE started_at >= :day",
                Map.of("day", now.toLocalDate().atStartOfDay()), String.class));
        return new Snapshot(now, lastSeen, fault, planned, upstream(equipment), startedToday, holding(now));
    }

    /**
     * 끝낸 제품을 들고 다음 단을 기다리는 프레스 1~3단: 그 단에서 끝났고(HOLD_SECONDS 넘게) 다음 단에 아직 안 들어간 제품이 있고,
     * 지금 처리 중인 제품은 없음. 공정 불량(폐기) 제품은 빼요
     */
    Set<String> holding(LocalDateTime now) {
        Set<String> held = new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT f.equipment_id FROM product_flow f JOIN equipment e ON e.equipment_id = f.equipment_id
                WHERE e.process_id = 'PRESS' AND e.seq_no < :last AND f.ended_at IS NOT NULL AND f.ended_at <= :hold
                  AND NOT EXISTS (SELECT 1 FROM product_flow g WHERE g.product_id = f.product_id AND g.flow_id > f.flow_id)
                  AND NOT EXISTS (SELECT 1 FROM press_data x WHERE x.product_id = f.product_id AND x.result = 'NG')""",
                Map.of("last", PRESS_STAGES.size(), "hold", now.minusSeconds(HOLD_SECONDS)), String.class));
        held.removeAll(flow.inProcess().keySet());
        return held;
    }

    /**
     * 설비마다 "처리 중 + 앞에서 기다리는 제품" 수 (product_flow). 0이면 처리할 게 없는 대기(I)예요.
     * 앞 = 슬리팅은 코일 재고, PRn은 같은 라인 PR(n-1) 뒤, 프레스 PR1은 슬리팅 뒤, 용접은 PR4 뒤,
     * 도장은 지금 이 도장으로 가는 라인들의 용접 뒤, 검사는 모든 도장 뒤
     */
    Map<String, Long> upstream(List<Equipment> equipment) {
        Map<String, Long> after = new HashMap<>(); // "공정|단|라인" 뒤 재공
        long paintBehind = 0;
        for (FlowStats.Waiting w : flow.waiting()) {
            after.merge(w.process() + "|" + ("PRESS".equals(w.process()) ? w.seq() : 0) + "|" + w.lineId(), w.qty(), Long::sum);
            if ("PAINTING".equals(w.process())) paintBehind += w.qty();
        }
        Map<String, Long> weldBehindByPaint = new HashMap<>();
        jdbc.query("SELECT line_id, current_paint_id FROM line", Map.of(), rs -> {
            weldBehindByPaint.merge(rs.getString(2), after.getOrDefault("WELDING|0|" + rs.getString(1), 0L), Long::sum);
        });
        long coil = flow.coilWaiting();
        Map<String, Long> busy = flow.inProcess();
        int lastStage = PRESS_STAGES.size();

        Map<String, Long> out = new HashMap<>();
        for (Equipment e : equipment) {
            String l = e.lineId();
            long waiting = switch (e.process()) {
                case "SLITTING" -> coil;
                case "PRESS" -> e.seq() <= 1 ? after.getOrDefault("SLITTING|0|" + l, 0L) : after.getOrDefault("PRESS|" + (e.seq() - 1) + "|" + l, 0L);
                case "WELDING" -> after.getOrDefault("PRESS|" + lastStage + "|" + l, 0L);
                case "PAINTING" -> weldBehindByPaint.getOrDefault(e.id(), 0L);
                case "INSPECTION" -> paintBehind;
                default -> 1L; // 모르는 공정은 대기로 보지 않아요
            };
            out.put(e.id(), waiting + busy.getOrDefault(e.id(), 0L));
        }
        return out;
    }
}
