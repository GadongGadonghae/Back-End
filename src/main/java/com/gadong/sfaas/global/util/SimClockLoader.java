package com.gadong.sfaas.global.util;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시연용 가상 시계 (11/21). DB sim_clock 한 줄(active = TRUE)을 1초마다 읽어 {@link Kst#now()}에 반영해요.
 * 가상 시각 = virtual_at + (지금 실제 시각 − real_at) × speed. 줄이 없거나 active = FALSE면 실제 시계.
 * 시연 스크립트가 "가상 09:00부터 ×10"처럼 이 줄을 쓰고, 재생기에도 같은 값을 줘서 전체가 한 시계로 움직여요.
 * 화면은 GET /api/sim/clock 으로 받아요 (로그인 전 화면 시계도 맞게 인증 없이).
 */
@Component
@RestController
public class SimClockLoader {
    private final NamedParameterJdbcTemplate jdbc;

    public SimClockLoader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 0)
    public void load() {
        try {
            List<Kst.Sim> rows = jdbc.query("SELECT virtual_at, real_at, speed FROM sim_clock WHERE active = TRUE ORDER BY id LIMIT 1", Map.of(),
                    (rs, n) -> new Kst.Sim(rs.getObject(1, LocalDateTime.class), rs.getObject(2, LocalDateTime.class), rs.getDouble(3)));
            Kst.setSim(rows.isEmpty() ? null : rows.get(0));
        } catch (RuntimeException e) {
            Kst.setSim(null);   // 표가 없는 DB(테스트 등)는 실제 시계
        }
    }

    /** 화면 시계: 지금 가상 시각과 배속 (가상 시계가 꺼져 있으면 실제 시각, speed 1) */
    @GetMapping("/api/sim/clock")
    public Map<String, Object> clock() {
        Kst.Sim s = Kst.sim();
        return Map.of("now", Kst.of(Kst.now()).toString(), "speed", s == null ? 1.0 : s.speed(), "active", s != null);
    }
}
