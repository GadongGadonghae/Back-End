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

/**
 * 제품 추적: G-01 unit=hour, P-01 제품 목록, P-02 제품 이력, P-03 공정 불량 제품 목록.
 * 프레스 PR2(우 압력)에서 NG 난 제품 P-PR2가 세 API 모두에서 A-PR2로 나오는지 봐요.
 */
@SpringBootTest
@WithMockUser(username = "testuser", roles = "MANAGER") // API는 로그인 필요 (SecurityConfig)
@AutoConfigureMockMvc
@Transactional
class ProductTraceApiTest {

    private static final String DAY = "2026-11-10";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('SLITTING','슬리팅',2), ('PRESS','프레스',3), "
                        + "('WELDING','용접',4), ('PAINTING','도장',5), ('INSPECTION','검사',6)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values ('A','A라인','PT1','PT1')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('A-SL','슬리터A','SLITTING','A',1,true), ('A-PR1','프레스A-1','PRESS','A',1,true), ('A-PR2','프레스A-2','PRESS','A',2,true), "
                        + "('A-PR3','프레스A-3','PRESS','A',3,true), ('A-PR4','프레스A-4','PRESS','A',4,true), ('A-WD','용접A','WELDING','A',1,true), "
                        + "('PT1','도장-1','PAINTING',null,1,true), ('VI','검사-1','INSPECTION',null,1,true)",
                // 실제 DB처럼 프레스는 단마다 자기 칸 기준 하나
                "insert into threshold(threshold_id, equipment_id, item_name, item_label, unit, lower_limit, upper_limit, updated_at) values "
                        + "(1,'A-PR1','pressure_left','좌 압력','bar',270,285.3,CURRENT_TIMESTAMP), "
                        + "(2,'A-PR2','pressure_right','우 압력','bar',270,285,CURRENT_TIMESTAMP), "
                        + "(3,'A-PR3','pressure_diff','압력 차','bar',0,10,CURRENT_TIMESTAMP), "
                        + "(4,'A-PR4','press_time','가압 시간','ms',540,560,CURRENT_TIMESTAMP), "
                        + "(5,'A-WD','current','전류','kA',14.5,15.1,CURRENT_TIMESTAMP)",
                "insert into slitting_data(equipment_id, product_id, measured_at, torque, result) values "
                        + "('A-SL','P-OK','2026-11-10 09:00:00',10,'OK'), ('A-SL','P-PR2','2026-11-10 09:05:00',10,'OK'), "
                        + "('A-SL','P-WIP','2026-11-10 09:10:00',10,'OK'), ('A-SL','P-STOP','2026-11-10 08:00:00',10,'OK')",
                // P-PR2: PR2 우 압력 290 > 285 → NG (행 equipment_id는 마지막에 쓴 A-PR2). P-WIP: PR1만 지남
                "insert into press_data(equipment_id, product_id, measured_at, pressure_left, pressure_right, pressure_diff, press_time, result) values "
                        + "('A-PR4','P-OK','2026-11-10 09:00:10',275,271,4,551,'OK'), "
                        + "('A-PR2','P-PR2','2026-11-10 09:05:10',275,290,null,null,'NG'), "
                        + "('A-PR1','P-WIP','2026-11-10 09:10:10',280,null,null,null,null), "
                        + "('A-PR4','P-STOP','2026-11-10 08:00:10',275,271,4,551,'OK')",
                // P-STOP: 용접에서 값은 범위 안인데 NG (설비 정지로 끊긴 제품)
                "insert into welding_data(equipment_id, product_id, measured_at, current, result) values "
                        + "('A-WD','P-OK','2026-11-10 09:01:00',15.0,'OK'), ('A-WD','P-STOP','2026-11-10 08:02:00',15.0,'NG')",
                "insert into painting_data(equipment_id, line_id, product_id, measured_at, result) values ('PT1','A','P-OK','2026-11-10 09:02:00','OK')",
                "insert into inspection_result(inspection_id, equipment_id, product_id, line_id, paint_equipment_id, result, inspected_at) values "
                        + "(1,'VI','P-OK','A','PT1','OK','2026-11-10 09:30:00')");
    }

    @Test
    void hourly_fillsEmptyHoursAndAccumulatesGood() throws Exception {
        mvc.perform(get("/api/production/summary").param("from", DAY).param("to", DAY).param("unit", "hour").param("lineId", "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(24))
                .andExpect(jsonPath("$[0].period").value("2026-11-10T00:00:00+09:00"))
                .andExpect(jsonPath("$[0].inspected").value(0))
                .andExpect(jsonPath("$[8].processNg").value(1)) // P-STOP 용접 NG 08:02
                .andExpect(jsonPath("$[9].lineId").value("A"))
                .andExpect(jsonPath("$[9].inspected").value(1))
                .andExpect(jsonPath("$[9].good").value(1))
                .andExpect(jsonPath("$[9].processNg").value(1)) // P-PR2 프레스 NG 09:05
                .andExpect(jsonPath("$[23].cumulativeGood").value(1));
        mvc.perform(get("/api/production/summary").param("from", DAY).param("to", DAY)) // 기존 day는 그대로
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actualQty").exists());
    }

    @Test
    void products_listWithFinalResultAndScrappedEquipment() throws Exception {
        mvc.perform(get("/api/products").param("from", DAY).param("to", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].productId").value("P-WIP")) // 투입 최신순
                .andExpect(jsonPath("$.items[0].finalResult").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[0].finishedAt").doesNotExist())
                .andExpect(jsonPath("$.items[1].productId").value("P-PR2"))
                .andExpect(jsonPath("$.items[1].finalResult").value("NG"))
                .andExpect(jsonPath("$.items[1].scrappedProcess").value("PRESS"))
                .andExpect(jsonPath("$.items[1].scrappedEquipmentId").value("A-PR2"))
                .andExpect(jsonPath("$.items[1].inputAt").value("2026-11-10T09:05:00+09:00"))
                .andExpect(jsonPath("$.items[1].finishedAt").value("2026-11-10T09:05:10+09:00"))
                .andExpect(jsonPath("$.items[2].productId").value("P-OK"))
                .andExpect(jsonPath("$.items[2].finalResult").value("OK"))
                .andExpect(jsonPath("$.items[2].scrappedProcess").doesNotExist())
                .andExpect(jsonPath("$.items[2].finishedAt").value("2026-11-10T09:30:00+09:00"))
                .andExpect(jsonPath("$.items[3].scrappedEquipmentId").value("A-WD"));
        mvc.perform(get("/api/products").param("from", DAY).param("to", DAY).param("result", "NG"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].productId").value("P-PR2"));
        mvc.perform(get("/api/products").param("from", DAY).param("to", DAY).param("result", "BAD")).andExpect(status().isBadRequest());
    }

    @Test
    void product_stepsSplitPressIntoStages() throws Exception {
        mvc.perform(get("/api/products/P-PR2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineId").value("A"))
                .andExpect(jsonPath("$.finalResult").value("NG"))
                .andExpect(jsonPath("$.scrappedEquipmentId").value("A-PR2"))
                .andExpect(jsonPath("$.steps.length()").value(3)) // 슬리팅, PR1, PR2 (PR3·PR4는 값이 없어 빠짐)
                .andExpect(jsonPath("$.steps[1].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$.steps[1].values.pressureLeft").value(275))
                .andExpect(jsonPath("$.steps[1].result").value("OK"))
                .andExpect(jsonPath("$.steps[2].equipmentId").value("A-PR2"))
                .andExpect(jsonPath("$.steps[2].values.pressureRight").value(290))
                .andExpect(jsonPath("$.steps[2].result").value("NG"));
        mvc.perform(get("/api/products/P-OK"))
                .andExpect(jsonPath("$.steps.length()").value(8)) // 슬리팅 + 프레스 4단 + 용접 + 도장 + 검사
                .andExpect(jsonPath("$.steps[4].equipmentId").value("A-PR4"))
                .andExpect(jsonPath("$.steps[7].process").value("INSPECTION"));
        mvc.perform(get("/api/products/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void product_stepTimesFromFlow_andInProcessStep() throws Exception {
        // P-WIP: 슬리팅·PR1을 지나 지금 PR2에서 처리 중 (PR2 값은 아직 없음)
        jdbc.update("insert into product_flow(product_id, equipment_id, line_id, started_at, ended_at) values "
                + "('P-WIP','A-SL','A','2026-11-10 09:09:44','2026-11-10 09:10:00'), ('P-WIP','A-PR1','A','2026-11-10 09:10:01','2026-11-10 09:10:10'), "
                + "('P-WIP','A-PR2','A','2026-11-10 09:10:12',null)");
        mvc.perform(get("/api/products/P-WIP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalResult").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].startedAt").value("2026-11-10T09:09:44+09:00"))
                .andExpect(jsonPath("$.steps[1].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$.steps[1].endedAt").value("2026-11-10T09:10:10+09:00"))
                .andExpect(jsonPath("$.steps[2].equipmentId").value("A-PR2")) // 처리 중: 값·결과·나간 시각 없음
                .andExpect(jsonPath("$.steps[2].process").value("PRESS"))
                .andExpect(jsonPath("$.steps[2].startedAt").value("2026-11-10T09:10:12+09:00"))
                .andExpect(jsonPath("$.steps[2].endedAt").doesNotExist())
                .andExpect(jsonPath("$.steps[2].result").doesNotExist());
    }

    @Test
    void processNg_firstBreachOrStopReason() throws Exception {
        mvc.perform(get("/api/production/process-ng").param("from", DAY).param("to", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].productId").value("P-PR2")) // 최신순
                .andExpect(jsonPath("$.items[0].process").value("PRESS"))
                .andExpect(jsonPath("$.items[0].equipmentId").value("A-PR2"))
                .andExpect(jsonPath("$.items[0].itemName").value("pressure_right"))
                .andExpect(jsonPath("$.items[0].measuredValue").value(290))
                .andExpect(jsonPath("$.items[0].upperLimit").value(285))
                .andExpect(jsonPath("$.items[0].reason").value("A-PR2 우 압력 상한 초과"))
                .andExpect(jsonPath("$.items[1].productId").value("P-STOP"))
                .andExpect(jsonPath("$.items[1].equipmentId").value("A-WD"))
                .andExpect(jsonPath("$.items[1].itemName").doesNotExist())
                .andExpect(jsonPath("$.items[1].reason").value("기준 이탈 없음 (공정 중단)"));
        mvc.perform(get("/api/production/process-ng").param("from", DAY).param("to", DAY).param("process", "PRESS"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/production/process-ng").param("from", DAY).param("to", DAY).param("process", "COIL"))
                .andExpect(status().isBadRequest());
    }
}
