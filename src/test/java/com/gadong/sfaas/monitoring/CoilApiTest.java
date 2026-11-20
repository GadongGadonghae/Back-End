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

/** M-16 코일 입고 현황: 그날 입고 롤·개수, 재고(전날 남은 것 포함), 슬리팅 투입 수 */
@SpringBootTest
@WithMockUser(username = "testuser", roles = "MANAGER")
@AutoConfigureMockMvc
@Transactional
class CoilApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('SLITTING','슬리팅',2)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values ('A','A라인','PT1','PT1')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values ('A-SL','슬리팅A','SLITTING','A',1,true)",
                // 전날 1롤 + 그날 2롤 = 60개
                "insert into coil_receipt(material_id, received_at, product_qty) values ('COIL-20261109-001','2026-11-09 08:00:00',20), "
                        + "('COIL-20261110-001','2026-11-10 08:00:00',20), ('COIL-20261110-002','2026-11-10 08:00:00',20)",
                // 슬리팅 투입: 전날 1개, 그날 6개 → 재고 60 − 7 = 53개 = 2.7롤 (2.65 반올림)
                "insert into product_flow(product_id, equipment_id, line_id, started_at, ended_at) values "
                        + "('A-0','A-SL','A','2026-11-09 08:10:00','2026-11-09 08:10:16'), "
                        + "('A-1','A-SL','A','2026-11-10 08:10:00','2026-11-10 08:10:16'), ('A-2','A-SL','A','2026-11-10 08:10:16','2026-11-10 08:10:32'), "
                        + "('A-3','A-SL','A','2026-11-10 08:10:32','2026-11-10 08:10:48'), ('A-4','A-SL','A','2026-11-10 08:10:48','2026-11-10 08:11:04'), "
                        + "('A-5','A-SL','A','2026-11-10 08:11:04','2026-11-10 08:11:20'), ('A-6','A-SL','A','2026-11-10 08:11:20',null)",
                // 슬리팅이 끝나면 측정값도 같이 남아요 (Node-RED가 같은 SQL로 씀). A-6은 아직 슬리팅 안
                "insert into slitting_data(product_id, equipment_id, measured_at) values ('A-0','A-SL','2026-11-09 08:10:16'), "
                        + "('A-1','A-SL','2026-11-10 08:10:16'), ('A-2','A-SL','2026-11-10 08:10:32'), ('A-3','A-SL','2026-11-10 08:10:48'), "
                        + "('A-4','A-SL','2026-11-10 08:11:04'), ('A-5','A-SL','2026-11-10 08:11:20')");
    }

    @Test
    void coilToday_countsReceiptsStockAndInput() throws Exception {
        mvc.perform(get("/api/coil/today").param("date", "2026-11-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-10"))
                .andExpect(jsonPath("$.receivedRolls").value(2))
                .andExpect(jsonPath("$.receivedQty").value(40))
                .andExpect(jsonPath("$.stockQty").value(53))
                .andExpect(jsonPath("$.stockRolls").value(2.7))
                .andExpect(jsonPath("$.inputQty").value(6))
                .andExpect(jsonPath("$.receipts.length()").value(2))
                .andExpect(jsonPath("$.receipts[0].materialId").value("COIL-20261110-001"))
                .andExpect(jsonPath("$.receipts[0].receivedAt").value("2026-11-10T08:00:00+09:00"))
                .andExpect(jsonPath("$.receipts[0].productQty").value(20));
    }

    @Test
    void coilToday_emptyDay() throws Exception {
        mvc.perform(get("/api/coil/today").param("date", "2026-11-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedRolls").value(0))
                .andExpect(jsonPath("$.stockQty").value(0))
                .andExpect(jsonPath("$.receipts.length()").value(0));
    }
}
