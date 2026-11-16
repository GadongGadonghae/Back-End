
package com.gadong.sfaas.master;

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

/** H2(메모리 DB)로 C-04, C-05와 공통 오류 응답을 확인해요. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "testuser", roles = "MANAGER")
class MasterApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private void seed() {
        jdbc.update("insert into process(process_id, process_name, seq) values ('PRESS','프레스',3)");
        jdbc.update("insert into process(process_id, process_name, seq) values ('SLITTING','슬리팅',2)");
        jdbc.update("insert into line(line_id, line_name, paint_equipment_id) values ('A','A라인','PT1')");
        jdbc.update("insert into line(line_id, line_name, paint_equipment_id) values ('B','B라인','PT1')");
        jdbc.update("insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values ('A-SL','A라인 슬리터','SLITTING','A',1,true)");
        jdbc.update("insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values ('A-PR2','A라인 프레스 2호기','PRESS','A',2,true)");
        jdbc.update("insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values ('A-PR1','A라인 프레스 1호기','PRESS','A',1,true)");
        jdbc.update("insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values ('B-PR1','B라인 프레스 1호기','PRESS','B',1,false)");
    }

    @Test
    void lines() throws Exception {
        seed();
        mvc.perform(get("/api/v1/lines"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].lineId").value("A"))
            .andExpect(jsonPath("$[0].defaultPaintId").value("PT1"));
    }

    @Test
    void equipmentsFilteredAndSorted() throws Exception {
        seed();
        mvc.perform(get("/api/v1/equipments").param("lineId", "A").param("process", "PRESS"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].equipmentId").value("A-PR1"))
            .andExpect(jsonPath("$[0].dataSource").value("OPCUA"));
        mvc.perform(get("/api/v1/equipments"))
            .andExpect(jsonPath("$.length()").value(3)); // 비활성 B-PR1 제외
    }

    @Test
    void unknownLineGivesErrorBody() throws Exception {
        seed();
        mvc.perform(get("/api/v1/equipments").param("lineId", "Z"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value("LINE_NOT_FOUND"))
            .andExpect(jsonPath("$.timestamp").exists());
    }
}
