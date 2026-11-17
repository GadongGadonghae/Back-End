package com.gadong.sfaas.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** M-14 시간대별 출고 양품, M-15 설비별 사이클 수·평균 CT */
@SpringBootTest
@WithMockUser(username = "testuser", roles = "MANAGER") // API는 로그인 필요 (SecurityConfig)
@AutoConfigureMockMvc
@Transactional
class TodayStatsApiTest {

    private static final String DAY = "2026-11-10";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('PRESS','프레스',3), ('WELDING','용접',4)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values ('A','A라인','PT1','PT1'), ('B','B라인','PT1','PT1')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('A-PR1','프레스A-1','PRESS','A',1,true), ('A-PR4','프레스A-4','PRESS','A',4,true), ('A-WD','용접A','WELDING','A',1,true)",
                "insert into production_target(target_date, line_id, target_qty, created_at) values ('2026-11-10','A',240,'2026-11-09 18:00:00')",
                "insert into inspection_result(inspection_id, equipment_id, product_id, line_id, paint_equipment_id, result, inspected_at) values "
                        + "(1,'VI','P-1','A','PT1','OK','2026-11-10 09:10:00'), (2,'VI','P-2','A','PT1','OK','2026-11-10 09:40:00'), "
                        + "(3,'VI','P-3','A','PT1','NG','2026-11-10 09:50:00'), (4,'VI','P-4','A','PT1','OK','2026-11-10 11:05:00'), "
                        + "(5,'VI','P-5','B','PT1','OK','2026-11-10 11:06:00')",
                // 제품 3개: 첫 둘은 PR4까지, 마지막은 PR1만 지남. 제품 간격 17초
                "insert into press_data(equipment_id, measured_at, pressure_left, pressure_right, pressure_diff, press_time) values "
                        + "('A-PR4','2026-11-10 09:00:00',275,271,4,551), ('A-PR4','2026-11-10 09:00:17',276,272,4,550), "
                        + "('A-PR1','2026-11-10 09:00:34',277,null,null,null)",
                "insert into welding_data(equipment_id, measured_at, current) values "
                        + "('A-WD','2026-11-10 09:00:00',15.0), ('A-WD','2026-11-10 09:00:20',15.0), ('A-WD','2026-11-10 09:00:40',15.0)");
    }

    @Test
    void hourly_cumulativeOkWithPlanLine() throws Exception {
        mvc.perform(get("/api/production/hourly").param("date", DAY).param("lineId", "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value(240))
                .andExpect(jsonPath("$.hours.length()").value(24)) // 지난 날은 0~23시
                .andExpect(jsonPath("$.hours[9].done").value(2)) // NG는 빼요
                .andExpect(jsonPath("$.hours[9].planCumulative").value(100)) // 240 × 10 ÷ 24
                .andExpect(jsonPath("$.hours[11].done").value(1))
                .andExpect(jsonPath("$.hours[23].cumulative").value(3));
        mvc.perform(get("/api/production/hourly").param("date", DAY)) // 공장 전체, 계획은 라인 합
                .andExpect(jsonPath("$.hours[23].cumulative").value(4))
                .andExpect(jsonPath("$.target").value(240));
        mvc.perform(get("/api/production/hourly").param("date", "2026-11-11").param("lineId", "B"))
                .andExpect(jsonPath("$.target").doesNotExist())
                .andExpect(jsonPath("$.hours[0].planCumulative").doesNotExist());
    }

    @Test
    void cycles_perEquipmentAndPressStage() throws Exception {
        mvc.perform(get("/api/monitoring/processes/WELDING/cycles").param("date", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].equipmentId").value("A-WD"))
                .andExpect(jsonPath("$[0].cycles").value(3))
                .andExpect(jsonPath("$[0].avgCtMs").value(20000));
        mvc.perform(get("/api/monitoring/processes/PRESS/cycles").param("date", DAY).param("lineId", "A"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$[0].cycles").value(3))
                .andExpect(jsonPath("$[0].avgCtMs").value(17000))
                .andExpect(jsonPath("$[3].equipmentId").value("A-PR4"))
                .andExpect(jsonPath("$[3].cycles").value(2));
        mvc.perform(get("/api/monitoring/processes/NOPE/cycles")).andExpect(status().isBadRequest());
    }
}
