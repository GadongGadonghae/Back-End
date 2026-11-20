package com.gadong.sfaas.availability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 생산 계획(G-02·G-03)과 재배정 보고서(R-01·R-02)를 H2 데이터로 확인해요. */
@SpringBootTest
@WithMockUser(username = "testuser", roles = "MANAGER") // API는 로그인 필요 (SecurityConfig)
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
        mvc.perform(put("/api/production/plans/2026-11-17").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"A\",\"planQty\":1500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-17"))
                .andExpect(jsonPath("$.planQty").value(1500));
        mvc.perform(put("/api/production/plans/2026-11-17").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"A\",\"planQty\":1600}"))
                .andExpect(jsonPath("$.planQty").value(1600));
        mvc.perform(get("/api/production/plans").param("from", "2026-11-17").param("to", "2026-11-17"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lineId").value("A"))
                .andExpect(jsonPath("$[0].planQty").value(1600));
        mvc.perform(put("/api/production/plans/2026-11-17").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"A\",\"planQty\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/production/plans/2026-11-17").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"Z\",\"planQty\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void r01r02Reports() throws Exception {
        mvc.perform(get("/api/reassignments").param("from", "2026-11-10").param("to", "2026-11-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].reportId").value(12))
                .andExpect(jsonPath("$.items[0].downtimeId").value(10))
                .andExpect(jsonPath("$.items[0].sourceLineId").value("A"))
                .andExpect(jsonPath("$.items[0].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$.items[0].stopMin").value(42))
                .andExpect(jsonPath("$.items[0].lossQty").value(126))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-10T09:55:00+09:00"))
                .andExpect(jsonPath("$.items[0].code").value("UP_BREAKDOWN"));
        mvc.perform(get("/api/reassignments/12"))
                .andExpect(jsonPath("$.downtimeId").value(10))
                .andExpect(jsonPath("$.sourceLineId").value("A"))
                .andExpect(jsonPath("$.details[0].targetLineId").value("B"))
                .andExpect(jsonPath("$.details[0].spareCapacity").value(300))
                .andExpect(jsonPath("$.details[0].addQty").value(126))
                .andExpect(jsonPath("$.equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$.code").value("UP_BREAKDOWN"));
        mvc.perform(get("/api/reassignments/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REASSIGNMENT_NOT_FOUND"));
        mvc.perform(patch("/api/reassignments/12/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()); // 승인 API는 없어요(v3)
    }

    @Test
    void s01s02s03PlannedStopLifecycle() throws Exception {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('SLITTING','슬리팅',2), ('WELDING','용접',4)",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('A-SL','슬리터A','SLITTING','A',1,true), ('A-PR2','프레스A-2','PRESS','A',2,true), ('A-WD','용접A','WELDING','A',1,true), "
                        + "('B-SL','슬리터B','SLITTING','B',1,true)",
                "insert into downtime_code(code, code_name, is_planned) values ('PL_PM','예방보전',true)");

        mvc.perform(post("/api/planned-stops").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"A\",\"reasonCode\":\"PL_PM\",\"startAt\":\"2026-11-10T12:00:00+09:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("S"))
                .andExpect(jsonPath("$.startAt").value("2026-11-10T12:00:00+09:00"))
                .andExpect(jsonPath("$.stopId").exists());
        long stopId = jdbc.queryForObject("select max(stop_group_id) from downtime_event", Long.class);
        mvc.perform(get("/api/planned-stops").param("from", "2026-11-10").param("to", "2026-11-10"))
                .andExpect(jsonPath("$.length()").value(1)) // 설비 4줄이 stopId 1건으로 묶여요
                .andExpect(jsonPath("$[0].stopId").value(stopId))
                .andExpect(jsonPath("$[0].lineId").value("A"))
                .andExpect(jsonPath("$[0].equipmentIds.length()").value(4)) // A-SL, A-PR1, A-PR2, A-WD
                .andExpect(jsonPath("$[0].equipmentIds[0]").value("A-SL")) // 공정 순서
                .andExpect(jsonPath("$[0].equipmentIds[3]").value("A-WD"))
                .andExpect(jsonPath("$[0].inProgress").value(true))
                .andExpect(jsonPath("$[0].endAt").isEmpty());

        mvc.perform(post("/api/planned-stops").contentType(MediaType.APPLICATION_JSON).content("{\"lineId\":\"A\",\"reasonCode\":\"PL_PM\"}"))
                .andExpect(status().isConflict()) // 같은 라인에 진행 중인 계획정지
                .andExpect(jsonPath("$.code").value("PLANNED_STOP_CONFLICT"));
        mvc.perform(post("/api/planned-stops").contentType(MediaType.APPLICATION_JSON).content("{\"lineId\":\"B\",\"reasonCode\":\"UP_BREAKDOWN\"}"))
                .andExpect(status().isBadRequest()); // 계획정지 사유가 아니에요

        mvc.perform(patch("/api/planned-stops/" + stopId + "/end").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endAt\":\"2026-11-10T13:00:00+09:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(60))
                .andExpect(jsonPath("$.endAt").value("2026-11-10T13:00:00+09:00"));
        mvc.perform(get("/api/planned-stops").param("from", "2026-11-10").param("to", "2026-11-10").param("inProgress", "false"))
                .andExpect(jsonPath("$[0].endAt").value("2026-11-10T13:00:00+09:00"));
        mvc.perform(patch("/api/planned-stops/" + stopId + "/end").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()); // 이미 종료
        mvc.perform(patch("/api/planned-stops/999/end").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLANNED_STOP_NOT_FOUND"));
    }
}
