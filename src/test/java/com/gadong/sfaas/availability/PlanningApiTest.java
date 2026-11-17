package com.gadong.sfaas.availability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 생산 계획(G-02·G-03)과 재배정 보고서(R-01·R-02)를 H2 데이터로 확인해요. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlanningApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('PRESS','프레스',3)",
                "insert into line(line_id, line_name) values ('A','A라인'), ('B','B라인')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values ('A-PR1','프레스A-1','PRESS','A',1,true)",
                "insert into downtime_code(code, code_name, is_planned) values ('UP_BREAKDOWN','장비고장',false)",
                "insert into users(user_id, login_id, password_hash, user_name, role) values (1,'u1','x','김관리','ADMIN')",
                "insert into downtime_event(downtime_id, equipment_id, status, code, started_at, ended_at) values "
                        + "(10,'A-PR1','D','UP_BREAKDOWN','2026-11-10 09:00:00','2026-11-10 09:42:00')",
                "insert into reassignment_report(report_id, downtime_id, loss_qty, created_at, approved) values (12,10,126,'2026-11-10 09:55:00',false)",
                "insert into reassignment_detail(report_id, line_id, spare_capacity, added_qty) values (12,'B',300,126)");
    }

    @Test
    void g02g03UpsertDailyPlan() throws Exception {
        mvc.perform(put("/api/v1/production/plans/2026-11-17").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"A\",\"planQty\":1500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-17"))
                .andExpect(jsonPath("$.planQty").value(1500));
        mvc.perform(put("/api/v1/production/plans/2026-11-17").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"A\",\"planQty\":1600}"))
                .andExpect(jsonPath("$.planQty").value(1600));
        mvc.perform(get("/api/v1/production/plans").param("from", "2026-11-17").param("to", "2026-11-17"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lineId").value("A"))
                .andExpect(jsonPath("$[0].planQty").value(1600));
        mvc.perform(put("/api/v1/production/plans/2026-11-17").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"A\",\"planQty\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/production/plans/2026-11-17").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"Z\",\"planQty\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void r01r02Reports() throws Exception {
        mvc.perform(get("/api/v1/reassignments").param("from", "2026-11-10").param("to", "2026-11-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reportId").value(12))
                .andExpect(jsonPath("$.content[0].sourceLineId").value("A"))
                .andExpect(jsonPath("$.content[0].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$.content[0].stopMin").value(42))
                .andExpect(jsonPath("$.content[0].lossQty").value(126))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-10T09:55:00+09:00"));
        mvc.perform(get("/api/v1/reassignments").param("from", "2026-11-10").param("to", "2026-11-10").param("status", "APPROVED"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/reassignments").param("from", "2026-11-10").param("to", "2026-11-10").param("status", "DONE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reassignments/12"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvedBy").isEmpty())
                .andExpect(jsonPath("$.sourceLineId").value("A"))
                .andExpect(jsonPath("$.details[0].targetLineId").value("B"))
                .andExpect(jsonPath("$.details[0].addQty").value(126));
        mvc.perform(get("/api/v1/reassignments/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REASSIGNMENT_NOT_FOUND"));
    }

    @Test
    void r03ApproveKeepsFirstApproval() throws Exception {
        mvc.perform(patch("/api/v1/reassignments/12/approve").contentType(MediaType.APPLICATION_JSON).content("{\"approvedBy\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").exists());
        em.flush();
        mvc.perform(get("/api/v1/reassignments").param("from", "2026-11-10").param("to", "2026-11-10").param("status", "APPROVED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("APPROVED"));
        mvc.perform(get("/api/v1/reassignments/12"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value(1));
        mvc.perform(patch("/api/v1/reassignments/12/approve").contentType(MediaType.APPLICATION_JSON).content("{\"approvedBy\":1}"))
                .andExpect(status().isOk()); // 다시 승인해도 오류 없이 처음 기록 유지
        mvc.perform(patch("/api/v1/reassignments/12/approve").contentType(MediaType.APPLICATION_JSON).content("{\"approvedBy\":999}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/reassignments/12/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/reassignments/999/approve").contentType(MediaType.APPLICATION_JSON).content("{\"approvedBy\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REASSIGNMENT_NOT_FOUND"));
    }
}
