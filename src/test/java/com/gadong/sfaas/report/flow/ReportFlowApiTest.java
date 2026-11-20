package com.gadong.sfaas.report.flow;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 보고서 결재·전송 (R-10~R-14) + 임시 MES 수신함 (M-01) + 라인 담당자 본인 라인 (v0.3.15) */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportFlowApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq, cycle_time_sec) values ('SLITTING','슬리팅',2,16), ('PRESS','프레스',3,17), "
                        + "('WELDING','용접',4,17), ('PAINTING','도장',5,4), ('INSPECTION','검사',6,2)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values "
                        + "('A','A라인','PT1','PT1'), ('B','B라인','PT1','PT1'), ('C','C라인','PT2','PT2')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('A-PR1','A라인 프레스 1호기','PRESS','A',1,true), ('B-PR1','B라인 프레스 1호기','PRESS','B',1,true), "
                        + "('PT1','도장 1호기','PAINTING',null,1,true), ('PT2','도장 2호기','PAINTING',null,2,true)",
                "insert into downtime_code(code, code_name, is_planned) values ('UP_BREAKDOWN','장비고장',false)",
                "insert into users(user_id, login_id, password_hash, user_name, role, lines) values "
                        + "(1,'lead','x','김팀장','PROD_LEAD',null), (2,'boss','x','박공장','PLANT_HEAD',null), "
                        + "(3,'campus','x','이캠퍼스','CAMPUS',null), (4,'lineA','x','최라인','LINE_WORKER','A')",
                "insert into production_target(target_date, line_id, target_qty, created_at) values "
                        + "('2026-11-10','A',100,'2026-11-09 00:00:00'), ('2026-11-10','B',100,'2026-11-09 00:00:00'), "
                        + "('2026-11-10','C',100,'2026-11-09 00:00:00')",
                "insert into downtime_event(downtime_id, equipment_id, status, code, detail, started_at, ended_at) values "
                        + "(10,'A-PR1','D','UP_BREAKDOWN','압력 상한 초과','2026-11-10 12:00:00','2026-11-10 12:40:00'), "
                        + "(11,'B-PR1','D','UP_BREAKDOWN',null,'2026-11-10 13:00:00','2026-11-10 13:10:00'), "
                        + "(12,'PT1','D','UP_BREAKDOWN',null,'2026-11-10 14:00:00','2026-11-10 14:10:00')");
    }

    private String idOf(String body, String field) {
        return body.replaceAll("(?s).*?\"" + field + "\":(\\d+).*", "$1");
    }

    @Test
    void writeApproveSend_thenMesInbox() throws Exception {
        String body = mvc.perform(post("/api/reports").with(user("lead").roles("PROD_LEAD")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"REPLAN\",\"targetKey\":\"10\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))   // 만들기만 하면 작성 중 (11/20)
                .andExpect(jsonPath("$.campusId").value("BODY"))
                .andExpect(jsonPath("$.factoryId").value(1))
                .andExpect(jsonPath("$.writtenBy.userName").value("김팀장"))
                .andExpect(jsonPath("$.replanReportId").exists())
                .andExpect(jsonPath("$.content.lossQty").exists())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(body, "reportId");

        String replanId = idOf(body, "replanReportId");

        // 작성 중: 공장장은 아직 결재 못 함. 팀장은 배정 값을 고칠 수 있음 (R-07)
        mvc.perform(post("/api/reports/" + id + "/approve").with(user("boss").roles("PLANT_HEAD")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_STATE_CONFLICT"));
        String detail = mvc.perform(get("/api/reassignments/" + replanId).with(user("lead").roles("PROD_LEAD")))
                .andReturn().getResponse().getContentAsString();
        String line = detail.replaceAll("(?s).*?\"targetLineId\":\"([A-Z0-9-]+)\".*", "$1");
        mvc.perform(put("/api/reassignments/" + replanId + "/allocations").with(user("lead").roles("PROD_LEAD"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"" + line + "\":7}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/reassignments/" + replanId + "/allocations").with(user("boss").roles("PLANT_HEAD"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"" + line + "\":7}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/reassignments/" + replanId + "/allocations").with(user("lead").roles("PROD_LEAD"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"Z\":7}")).andExpect(status().isBadRequest());

        // 작성 완료 · 결재 요청 (R-15) → 회수 (R-16) → 다시 결재 요청. 결재 요청 중에는 값을 못 고침
        mvc.perform(post("/api/reports/" + id + "/request").with(user("lead").roles("PROD_LEAD")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WRITTEN"))
                .andExpect(jsonPath("$.writtenBy.userName").value("김팀장"));
        mvc.perform(put("/api/reassignments/" + replanId + "/allocations").with(user("lead").roles("PROD_LEAD"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"" + line + "\":8}")).andExpect(status().isConflict());
        mvc.perform(post("/api/reports/" + id + "/request").with(user("lead").roles("PROD_LEAD"))).andExpect(status().isConflict());
        mvc.perform(post("/api/reports/" + id + "/recall").with(user("boss").roles("PLANT_HEAD"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/reports/" + id + "/recall").with(user("lead").roles("PROD_LEAD")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(post("/api/reports/" + id + "/request").with(user("lead").roles("PROD_LEAD")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WRITTEN"));

        // 권한·순서
        mvc.perform(post("/api/reports").with(user("boss").roles("PLANT_HEAD")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"REPLAN\",\"targetKey\":\"10\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/reports/" + id + "/send").with(user("boss").roles("PLANT_HEAD")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_STATE_CONFLICT"));
        mvc.perform(post("/api/reports/" + id + "/approve").with(user("lead").roles("PROD_LEAD"))).andExpect(status().isForbidden());

        // 결재 → 계획수정 보고서도 승인·적용 (B·C 목표가 늘어남)
        mvc.perform(post("/api/reports/" + id + "/approve").with(user("boss").roles("PLANT_HEAD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy.userName").value("박공장"))
                .andExpect(jsonPath("$.content.approved").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select target_qty from production_target where target_date = '2026-11-10' and line_id = 'B'", Integer.class)).isGreaterThan(100);

        // 캠퍼스장: 전송 전에는 안 보임
        mvc.perform(get("/api/mes/reports").with(user("campus").roles("CAMPUS"))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/reports/" + id).with(user("campus").roles("CAMPUS"))).andExpect(status().isNotFound());

        mvc.perform(post("/api/reports/" + id + "/send").with(user("boss").roles("PLANT_HEAD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentBy.userName").value("박공장"))
                .andExpect(jsonPath("$.sentAt").exists());
        mvc.perform(get("/api/mes/reports").with(user("campus").roles("CAMPUS")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("SENT"))
                .andExpect(jsonPath("$.items[0].content").exists());   // 수신함은 내용까지 (다른 컴퓨터의 캠퍼스장 열람, 11/20)
        mvc.perform(get("/api/reports/" + id).with(user("campus").roles("CAMPUS")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").exists());
        mvc.perform(get("/api/mes/reports").with(user("boss").roles("PLANT_HEAD"))).andExpect(status().isForbidden());

        // 전송한 보고서는 다시 전송 못 함. 같은 고장으로 계획을 다시 고치면 새 보고서·새 결재 (11/20)
        mvc.perform(post("/api/reports/" + id + "/send").with(user("boss").roles("PLANT_HEAD"))).andExpect(status().isConflict());
        String again = mvc.perform(post("/api/reports").with(user("lead").roles("PROD_LEAD")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"REPLAN\",\"targetKey\":\"10\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(idOf(again, "reportId")).isNotEqualTo(id);
    }

    @Test
    void oldR03R04_areRecordedInFlow_andDailyStoresContent() throws Exception {
        // 예전 화면 흐름(R-03 만들기 · R-04 승인)도 결재 흐름에 기록 → 그대로 전송 가능
        String body = mvc.perform(post("/api/reassignments").with(user("lead").roles("PROD_LEAD")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"downtimeId\":12}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replanId = idOf(body, "reportId");
        mvc.perform(get("/api/reports").with(user("lead").roles("PROD_LEAD")).param("replanId", replanId))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].targetKey").value("12#" + replanId));   // 보고서마다 따로 (11/20)
        mvc.perform(post("/api/reassignments/" + replanId + "/approve").with(user("boss").roles("PLANT_HEAD"))).andExpect(status().isOk());
        mvc.perform(get("/api/reports").with(user("lead").roles("PROD_LEAD")).param("replanId", replanId))
                .andExpect(jsonPath("$.items[0].status").value("APPROVED"));

        // 일일 보고서: 화면이 계산한 내용을 저장 (같은 날 다시 쓰면 같은 보고서를 다시 작성)
        mvc.perform(post("/api/reports").with(user("lead").roles("PROD_LEAD")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DAILY\",\"targetKey\":\"2026-11-10\",\"content\":{\"produced\":1200}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("일일 생산 보고서 · 2026-11-10"))
                .andExpect(jsonPath("$.content.produced").value(1200));
        mvc.perform(post("/api/reports").with(user("lead").roles("PROD_LEAD")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DAILY\",\"targetKey\":\"2026-11-10\",\"content\":{\"produced\":1300}}"))
                .andExpect(jsonPath("$.content.produced").value(1300));
        mvc.perform(get("/api/reports").with(user("lead").roles("PROD_LEAD")).param("kind", "DAILY")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(post("/api/reports").with(user("lead").roles("PROD_LEAD")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"DAILY\",\"targetKey\":\"2026-11-10\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void lineWorker_writesActionOnlyOnOwnLine() throws Exception {
        // 라인 담당자 lineA(담당 A): A라인 설비는 되고, B라인·공용 도장은 403
        mvc.perform(patch("/api/downtime/events/10").with(user("lineA").roles("LINE_WORKER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memo\":\"금형 점검\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/downtime/events/11").with(user("lineA").roles("LINE_WORKER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memo\":\"x\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(patch("/api/downtime/events/12").with(user("lineA").roles("LINE_WORKER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memo\":\"x\"}")).andExpect(status().isForbidden());
        // 생산관리팀장은 모든 라인
        mvc.perform(patch("/api/downtime/events/11").with(user("lead").roles("PROD_LEAD")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memo\":\"x\"}")).andExpect(status().isOk());
    }
}
