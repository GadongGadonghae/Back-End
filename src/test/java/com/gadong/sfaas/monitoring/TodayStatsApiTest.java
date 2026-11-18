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

/** M-15 설비별 사이클 수·평균 CT, M-03 프레스 단별 그래프, M-13 도장 설비별 재공 */
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
                "insert into press_data(product_id, equipment_id, measured_at, pressure_left, pressure_right, pressure_diff, press_time) values "
                        + "('X1','A-PR4','2026-11-10 08:59:17',275,271,4,551), ('X2','A-PR4','2026-11-10 08:59:34',276,272,4,550), "
                        + "('X3','A-PR1','2026-11-10 08:59:51',277,null,null,null)",
                // 단마다 들어간·나간 시각. PR1은 17초마다 3개, PR4는 2개 (PR2·PR3은 이 시드에 없음)
                "insert into product_flow(product_id, equipment_id, line_id, started_at, ended_at) values "
                        + "('X1','A-PR1','A','2026-11-10 08:59:00','2026-11-10 08:59:17'), ('X2','A-PR1','A','2026-11-10 08:59:17','2026-11-10 08:59:34'), "
                        + "('X3','A-PR1','A','2026-11-10 08:59:34','2026-11-10 08:59:51'), "
                        + "('X1','A-PR4','A','2026-11-10 09:00:00','2026-11-10 09:00:17'), ('X2','A-PR4','A','2026-11-10 09:00:17','2026-11-10 09:00:34'), "
                        + "('W1','A-WD','A','2026-11-10 09:00:00','2026-11-10 09:00:17'), ('W2','A-WD','A','2026-11-10 09:00:20','2026-11-10 09:00:37'), "
                        + "('W3','A-WD','A','2026-11-10 09:00:40','2026-11-10 09:00:57'), ('W4','A-WD','A','2026-11-10 10:00:00','2026-11-10 10:00:17')",
                "insert into welding_data(equipment_id, measured_at, current) values "
                        + "('A-WD','2026-11-10 09:00:00',15.0), ('A-WD','2026-11-10 09:00:20',15.0), ('A-WD','2026-11-10 09:00:40',15.0), "
                        + "('A-WD','2026-11-10 10:00:00',15.0)"); // 1시간 멈춘 뒤 다시 1개
    }

    @Test
    void cycles_perEquipmentAndPressStage() throws Exception {
        mvc.perform(get("/api/monitoring/processes/WELDING/cycles").param("date", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].equipmentId").value("A-WD"))
                .andExpect(jsonPath("$[0].cycles").value(4))
                .andExpect(jsonPath("$[0].avgCtMs").value(20000)); // 간격 중앙값이라 멈춘 1시간이 안 끼어요
        mvc.perform(get("/api/monitoring/processes/PRESS/cycles").param("date", DAY).param("lineId", "A"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$[0].cycles").value(3))
                .andExpect(jsonPath("$[0].avgCtMs").value(17000)) // 단마다 들어간 시각 간격 (product_flow)
                .andExpect(jsonPath("$[1].equipmentId").value("A-PR4"))
                .andExpect(jsonPath("$[1].cycles").value(2))
                .andExpect(jsonPath("$[1].avgCtMs").value(17000));
        mvc.perform(get("/api/monitoring/processes/NOPE/cycles")).andExpect(status().isBadRequest());
    }

    @Test
    void pressMetrics_readOwnStageColumnAcrossLineRows() throws Exception {
        // 행의 equipment_id가 A-PR4로 바뀌어 있어도 PR1 그래프는 PR1을 지난 제품의 좌 압력 3점 (시각 = PR1에서 나온 시각)
        mvc.perform(get("/api/equipments/A-PR1/metrics").param("from", DAY).param("to", DAY).param("interval", "raw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.length()").value(1))
                .andExpect(jsonPath("$.metrics[0].metric").value("pressureLeft"))
                .andExpect(jsonPath("$.metrics[0].points.length()").value(3));
        mvc.perform(get("/api/equipments/A-PR4/metrics").param("from", DAY).param("to", DAY).param("interval", "raw"))
                .andExpect(jsonPath("$.metrics[0].metric").value("pressTime"))
                .andExpect(jsonPath("$.metrics[0].points.length()").value(2));
    }

    @Test
    void paintWip_beforeAndAfterOneBooth() throws Exception {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('PAINTING','도장',5)",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('B-WD','용접B','WELDING','B',1,true), ('PT2','도장-2','PAINTING',null,2,true)",
                "update line set current_paint_id = 'PT2' where line_id = 'B'",
                "insert into welding_data(product_id, equipment_id, measured_at, current, result) values "
                        + "('B-1','B-WD','2026-11-10 09:00:00',15.0,'OK'), ('B-2','B-WD','2026-11-10 09:00:20',15.0,'OK')",
                "insert into painting_data(product_id, equipment_id, line_id, measured_at, result) values ('B-1','PT2','B','2026-11-10 09:01:00','OK')",
                "insert into product_flow(product_id, equipment_id, line_id, started_at, ended_at) values "
                        + "('B-1','B-WD','B','2026-11-10 08:59:43','2026-11-10 09:00:00'), ('B-1','PT2','B','2026-11-10 09:00:56','2026-11-10 09:01:00'), "
                        + "('B-2','B-WD','B','2026-11-10 09:00:03','2026-11-10 09:00:20')");
        mvc.perform(get("/api/production/wip").param("paintId", "PT2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].process").value("WELDING"))
                .andExpect(jsonPath("$[0].wipQty").value(1)) // B-2가 용접 뒤에서 PT2를 기다려요 (A는 PT1으로 가서 빠짐)
                .andExpect(jsonPath("$[1].process").value("PAINTING"))
                .andExpect(jsonPath("$[1].wipQty").value(1)); // B-1이 PT2에서 나와 검사를 기다려요
        mvc.perform(get("/api/production/wip").param("paintId", "A-WD")).andExpect(status().isBadRequest());
    }
}
