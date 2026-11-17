package com.gadong.sfaas.availability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * OEE 세 요소와 라인 수율을 손으로 계산한 값과 맞춰 봐요. 설비 1대(X-SL), 하루(86,400초):
 * 계획정지 S 4시간 → 부하 72,000초. 고장 D 5시간 → 가동 54,000초 → 시간가동률 75%.
 * 표준 CT 2,700초 × 10개 = 27,000초 → 성능가동률 50%. 판정 10개 중 OK 8 → 양품률 80%. OEE = 0.75 × 0.5 × 0.8 = 30%.
 * 출고 양품 4 ÷ 슬리팅 투입 10 → 라인 수율 40%.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OeeTest {

    private static final String DAY = "2026-11-10";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        StringBuilder slitting = new StringBuilder("insert into slitting_data(equipment_id, measured_at, result) values ");
        for (int i = 0; i < 10; i++) {
            slitting.append(i == 0 ? "" : ", ").append("('X-SL','2026-11-10 08:00:0").append(i).append("','").append(i < 8 ? "OK" : "NG").append("')");
        }
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq, cycle_time_sec) values ('SLITTING','슬리팅',2,2700)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values ('X','X라인','PT1','PT1')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values ('X-SL','슬리터X','SLITTING','X',1,true)",
                "insert into downtime_code(code, code_name, is_planned) values ('UP_BREAKDOWN','장비고장',false), ('PL_PM','예방보전',true)",
                "insert into downtime_event(downtime_id, equipment_id, status, code, started_at, ended_at) values "
                        + "(1,'X-SL','S','PL_PM','2026-11-10 00:00:00','2026-11-10 04:00:00'), "
                        + "(2,'X-SL','D','UP_BREAKDOWN','2026-11-10 10:00:00','2026-11-10 15:00:00')",
                slitting.toString(),
                "insert into inspection_result(inspection_id, equipment_id, product_id, line_id, paint_equipment_id, result, inspected_at) values "
                        + "(1,'VI','P-1','X','PT1','OK','2026-11-10 20:00:00'), (2,'VI','P-2','X','PT1','OK','2026-11-10 20:01:00'), "
                        + "(3,'VI','P-3','X','PT1','OK','2026-11-10 20:02:00'), (4,'VI','P-4','X','PT1','OK','2026-11-10 20:03:00'), "
                        + "(5,'VI','P-5','X','PT1','NG','2026-11-10 20:04:00')");
    }

    @Test
    void oeeFactorsAndLineYield() throws Exception {
        mvc.perform(get("/api/utilization/summary").param("from", DAY).param("to", DAY).param("lineId", "X"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value(75.0))
                .andExpect(jsonPath("$.performance").value(50.0))
                .andExpect(jsonPath("$.quality").value(80.0))
                .andExpect(jsonPath("$.oee").value(30.0))
                .andExpect(jsonPath("$.lineYield").value(40.0));
        mvc.perform(get("/api/utilization/trend").param("from", DAY).param("to", DAY).param("lineId", "X"))
                .andExpect(jsonPath("$[0].availability").value(75.0))
                .andExpect(jsonPath("$[0].performance").value(50.0))
                .andExpect(jsonPath("$[0].quality").value(80.0))
                .andExpect(jsonPath("$[0].oee").value(30.0));
        mvc.perform(get("/api/utilization/by-line").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$[0].oee").value(30.0))
                .andExpect(jsonPath("$[0].lineYield").value(40.0));
        mvc.perform(get("/api/utilization/by-process").param("from", DAY).param("to", DAY).param("lineId", "X"))
                .andExpect(jsonPath("$[0].oee").value(30.0));
        mvc.perform(get("/api/utilization/by-equipment").param("from", DAY).param("to", DAY).param("lineId", "X"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].oee").value(30.0));
    }
}
