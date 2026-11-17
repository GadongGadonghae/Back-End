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
import org.springframework.stereotype.Service;

/** 판정 설계 3·4절: 설비 상태(RSID) 판정. 상태가 바뀐 설비만 이력을 저장하고 W-01을 보내요. */
@Service
public class StatusJudgeService {
    private final NamedParameterJdbcTemplate jdbc;
    private final MonitoringService monitoringService;
    private final PushService pushService;

    public StatusJudgeService(NamedParameterJdbcTemplate jdbc, MonitoringService monitoringService, PushService pushService) {
        this.jdbc = jdbc;
        this.monitoringService = monitoringService;
        this.pushService = pushService;
    }

    public void judge() {
        Map<String, String> open = new HashMap<>();
        jdbc.query("SELECT equipment_id, status FROM equipment_status_history WHERE ended_at IS NULL", Map.of(),
                rs -> { open.put(rs.getString("equipment_id"), rs.getString("status").trim()); });

        LocalDateTime now = Kst.now();
        Set<String> changed = new HashSet<>();
        for (String id : jdbc.queryForList("SELECT equipment_id FROM equipment WHERE is_active = TRUE", Map.of(), String.class)) {
            String status = decideStatus(id);
            if (status.equals(open.get(id))) continue;
            // 닫기만 되고 저장이 실패해도 다음 초에 열린 줄이 없으니 다시 저장돼요
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("status", status).addValue("now", now);
            jdbc.update("UPDATE equipment_status_history SET ended_at = :now WHERE equipment_id = :id AND ended_at IS NULL", p);
            jdbc.update("INSERT INTO equipment_status_history(equipment_id, status, started_at) VALUES (:id, :status, :now)", p);
            changed.add(id);
        }
        if (changed.isEmpty()) return;

        // M-01과 같은 값으로 보내요 (명세: 상태 공통 필드는 어느 API로 봐도 같음)
        List<EquipmentStatusResponse> statuses = monitoringService.status(null);
        statuses.stream().filter(s -> changed.contains(s.equipmentId())).forEach(pushService::pushStatus);
    }

    /** 임시 규칙: 전부 R. TODO(판정 설계 3절): D > DC > S > I > R 우선순위, I·D면 downtime_event 연결 */
    String decideStatus(String equipmentId) {
        return "R";
    }
}
