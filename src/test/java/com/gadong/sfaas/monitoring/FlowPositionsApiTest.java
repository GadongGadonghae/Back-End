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

/** M-17 공장 안 제품 위치: 설비 안이면 equipmentId, 공정 사이면 pile. 프레스 1~3단이 들고 있는 제품은 그 프레스 안. 폐기·출고는 빠져요 */
@SpringBootTest
@WithMockUser(username = "testuser", roles = "MANAGER")
@AutoConfigureMockMvc
@Transactional
class FlowPositionsApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('SLITTING','슬리팅',2), ('PRESS','프레스',3), "
                        + "('WELDING','용접',4), ('PAINTING','도장',5), ('INSPECTION','검사',6)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values ('A','A라인','PT1','PT1')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('A-SL','슬리팅A','SLITTING','A',1,true), ('A-PR1','프레스A-1','PRESS','A',1,true), ('A-PR4','프레스A-4','PRESS','A',4,true), "
                        + "('A-WD','용접A','WELDING','A',1,true), ('PT1','도장-1','PAINTING',null,1,true), ('VI','검사-1','INSPECTION',null,1,true)",
                "insert into product_flow(product_id, equipment_id, line_id, started_at, ended_at) values "
                        + "('P1','A-SL','A','2026-11-19 09:00:00','2026-11-19 09:00:16'), "      // 슬리팅 뒤 → SL-PR
                        + "('P2','A-SL','A','2026-11-19 09:00:16','2026-11-19 09:00:32'), "
                        + "('P2','A-PR1','A','2026-11-19 09:00:32',null), "                       // PR1 처리 중 → A-PR1
                        + "('P3','A-PR1','A','2026-11-19 09:00:00','2026-11-19 09:00:17'), "     // PR1이 끝내고 들고 있음 → A-PR1 안
                        + "('P4','A-PR4','A','2026-11-19 09:01:00','2026-11-19 09:01:17'), "     // PR4 뒤 → PR-WD
                        + "('P5','A-WD','A','2026-11-19 09:02:00','2026-11-19 09:02:17'), "      // 용접 뒤 → WD-PT
                        + "('P6','PT1','A','2026-11-19 09:03:00','2026-11-19 09:03:04'), "       // 도장 뒤 → PT1:PT-VI
                        + "('P7','VI','A','2026-11-19 09:04:00','2026-11-19 09:04:02'), "        // 출고 → 빠짐
                        + "('P8','VI','A','2026-11-19 09:05:00',null), "                         // 검사 중 → VI
                        + "('P9','A-PR4','A','2026-11-19 09:01:20','2026-11-19 09:01:37')",      // 공정 NG 폐기 → 빠짐
                "insert into press_data(product_id, equipment_id, measured_at, result) values ('P9','A-PR4','2026-11-19 09:00:40','NG')");
    }

    @Test
    void positions_inEquipmentOrPile_excludingScrappedAndShipped() throws Exception {
        mvc.perform(get("/api/production/flow/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.at").exists())
                .andExpect(jsonPath("$.products.length()").value(7))
                .andExpect(jsonPath("$.products[0].productId").value("P1"))
                .andExpect(jsonPath("$.products[0].pile").value("SL-PR"))
                .andExpect(jsonPath("$.products[0].equipmentId").doesNotExist())
                .andExpect(jsonPath("$.products[1].productId").value("P2"))
                .andExpect(jsonPath("$.products[1].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$.products[1].pile").doesNotExist())
                .andExpect(jsonPath("$.products[2].productId").value("P3"))
                .andExpect(jsonPath("$.products[2].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$.products[2].pile").doesNotExist())
                .andExpect(jsonPath("$.products[3].pile").value("PR-WD"))
                .andExpect(jsonPath("$.products[4].pile").value("WD-PT"))
                .andExpect(jsonPath("$.products[5].pile").value("PT1:PT-VI"))
                .andExpect(jsonPath("$.products[6].productId").value("P8"))
                .andExpect(jsonPath("$.products[6].equipmentId").value("VI"))
                .andExpect(jsonPath("$.products[6].lineId").value("A"));
    }
}
