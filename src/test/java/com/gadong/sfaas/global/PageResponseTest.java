package com.gadong.sfaas.global;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 목록 공통 형식 { items, total, page, size }: page는 1부터, total은 페이지와 상관없는 전체 건수 */
@SpringBootTest
@WithMockUser(username = "testuser", roles = "MANAGER") // API는 로그인 필요 (SecurityConfig)
@AutoConfigureMockMvc
@Transactional
class PageResponseTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void secondPageOfSixty() throws Exception {
        for (int i = 1; i <= 60; i++) {
            jdbc.update("insert into alarm(alarm_id, equipment_id, level, message, raised_at) values (?, 'A-PR1', 'CRITICAL', 'm', ?)",
                    i, java.time.LocalDateTime.of(2026, 11, 10, 9, 0).plusMinutes(i));
        }
        mvc.perform(get("/api/alarms").param("from", "2026-11-10").param("to", "2026-11-10").param("page", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.total").value(60))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50));
        mvc.perform(get("/api/alarms").param("from", "2026-11-10").param("to", "2026-11-10"))
                .andExpect(jsonPath("$.items.length()").value(50)) // 기본 page=1, size=50
                .andExpect(jsonPath("$.page").value(1));
        mvc.perform(get("/api/alarms").param("from", "2026-11-10").param("to", "2026-11-10").param("page", "0"))
                .andExpect(status().isBadRequest());
    }
}
