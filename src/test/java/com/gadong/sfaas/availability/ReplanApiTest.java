package com.gadong.sfaas.availability;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * R-03 계획수정 보고서 만들기 → 저장 → R-01·R-02 조회.
 * 지난 날(11/10) 12:00 고장이라 기준 시각 = 고장 시각, 남은 시간 12시간(43200초), 라인 CT = 17초 → 최대 2541개.
 * 목표 A·B·C 100개씩, 12시 전 양품 A 2 · B 1 · C 0 → 부족 98, 남은 능력 B 2541−99=2442 · C 2541−100=2441 → B 49 · C 49
 */
@SpringBootTest
@WithMockUser(username = "u1", roles = "PRODUCTION_MANAGER")
@AutoConfigureMockMvc
@Transactional
class ReplanApiTest {

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
                        + "('PT1','도장 1호기','PAINTING',null,1,true), ('PT2','도장 2호기','PAINTING',null,2,true), "
                        + "('VI','비전 검사 1호기','INSPECTION',null,1,true)",
                "insert into downtime_code(code, code_name, is_planned) values ('UP_BREAKDOWN','장비고장',false), ('UP_SHORTAGE','자재부족',false)",
                "insert into users(user_id, login_id, password_hash, user_name, role) values (1,'u1','x','김관리','MANAGER')",
                "insert into production_target(target_date, line_id, target_qty, created_at) values "
                        + "('2026-11-10','A',100,'2026-11-09 00:00:00'), ('2026-11-10','B',100,'2026-11-09 00:00:00'), "
                        + "('2026-11-10','C',100,'2026-11-09 00:00:00')",
                "insert into inspection_result(equipment_id, product_id, line_id, paint_equipment_id, result, inspected_at) values "
                        + "('VI','A-1','A','PT1','OK','2026-11-10 10:00:00'), ('VI','A-2','A','PT1','OK','2026-11-10 11:00:00'), "
                        + "('VI','A-3','A','PT1','NG','2026-11-10 11:10:00'), "      // 불량은 실적 아님
                        + "('VI','B-1','B','PT1','OK','2026-11-10 11:30:00'), "
                        + "('VI','B-2','B','PT1','OK','2026-11-10 13:00:00')",       // 기준 시각(12:00) 뒤는 실적 아님
                "insert into downtime_event(downtime_id, equipment_id, status, code, detail, started_at, ended_at) values "
                        + "(10,'A-PR1','D','UP_BREAKDOWN','압력 상한 초과','2026-11-10 12:00:00','2026-11-10 12:40:00'), "
                        + "(11,'B-PR1','I','UP_SHORTAGE',null,'2026-11-10 12:00:00','2026-11-10 12:05:00'), "
                        + "(12,'PT1','D','UP_BREAKDOWN',null,'2026-11-10 12:00:00','2026-11-10 12:30:00')");
    }

    @Test
    void r03CreatesLineReport_savesAndReadsBack() throws Exception {
        String body = mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").exists())
                .andExpect(jsonPath("$.documentNo").exists())
                .andExpect(jsonPath("$.downtimeId").value(10))
                .andExpect(jsonPath("$.sourceLineId").value("A"))
                .andExpect(jsonPath("$.equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$.code").value("UP_BREAKDOWN"))
                .andExpect(jsonPath("$.detail").value("압력 상한 초과"))
                .andExpect(jsonPath("$.kind").value("line"))
                .andExpect(jsonPath("$.baseAt").value("2026-11-10T12:00:00+09:00"))
                .andExpect(jsonPath("$.remainingSec").value(43200))
                .andExpect(jsonPath("$.ctSec").value(17.0))
                .andExpect(jsonPath("$.stoppedPlan").value(100))
                .andExpect(jsonPath("$.stoppedDone").value(2))
                .andExpect(jsonPath("$.lossQty").value(98))
                .andExpect(jsonPath("$.targetQty").value(300))
                .andExpect(jsonPath("$.unmetQty").value(0))
                .andExpect(jsonPath("$.createdBy").value("김관리"))
                .andExpect(jsonPath("$.details.length()").value(2))
                .andExpect(jsonPath("$.details[0].targetLineId").value("B"))
                .andExpect(jsonPath("$.details[0].remainingPlan").value(99))
                .andExpect(jsonPath("$.details[0].spareCapacity").value(2442))
                .andExpect(jsonPath("$.details[0].addQty").value(49))
                .andExpect(jsonPath("$.details[0].share").value(50.0))
                .andExpect(jsonPath("$.details[1].targetLineId").value("C"))
                .andExpect(jsonPath("$.details[1].addQty").value(49))
                .andExpect(jsonPath("$.ai.title").value("A라인 생산계획 수정 제안"))
                .andExpect(jsonPath("$.ai.mock").value(true))
                .andExpect(jsonPath("$.ai.actions.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll("(?s).*?\"reportId\":(\\d+).*", "$1");


        mvc.perform(get("/api/reassignments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lossQty").value(98))
                .andExpect(jsonPath("$.details[1].addQty").value(49))
                .andExpect(jsonPath("$.ai.summary").exists());
        mvc.perform(get("/api/reassignments").param("from", "2000-01-01").param("to", "2100-12-31"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$.items[0].code").value("UP_BREAKDOWN"));
    }

    @Test
    void r04PlantManagerApproves_othersForbidden() throws Exception {
        // 11/20 계정 권한: 작성은 생산관리팀장(이 클래스 기본), 승인(결재)은 공장장만
        jdbc.update("insert into users(user_id, login_id, password_hash, user_name, role) values (2,'boss','x','박공장','PLANT_MANAGER')");
        String body = mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approved").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll("(?s).*?\"reportId\":(\\d+).*", "$1");
        mvc.perform(post("/api/reassignments/" + id + "/approve")).andExpect(status().isForbidden());   // 생산관리팀장은 승인 못 함
        mvc.perform(post("/api/reassignments/" + id + "/approve").with(user("boss").roles("PLANT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.approvedBy").value("박공장"))
                .andExpect(jsonPath("$.approvedAt").exists());
        mvc.perform(get("/api/reassignments").param("from", "2000-01-01").param("to", "2100-12-31"))
                .andExpect(jsonPath("$.items[0].approved").value(true));
        // 공장장·라인 담당자는 보고서를 만들 수 없어요
        mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":10}")
                        .with(user("boss").roles("PLANT_MANAGER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":10}")
                        .with(user("line").roles("LINE_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/reassignments/999/approve").with(user("boss").roles("PLANT_MANAGER"))).andExpect(status().isNotFound());
    }

    @Test
    void r04ApproveAppliesLinePlan() throws Exception {
        // 11/20: 승인하면 재배정 표대로 라인 목표를 바꿔요. A라인(고장) 남은 계획 98개 → B·C에 49개씩, A는 실적(2) 아래로 안 내림
        jdbc.update("insert into users(user_id, login_id, password_hash, user_name, role) values (2,'boss','x','박공장','PLANT_MANAGER')");
        String body = mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":10}"))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll("(?s).*?\"reportId\":(\\d+).*", "$1");
        mvc.perform(post("/api/reassignments/" + id + "/approve").with(user("boss").roles("PLANT_MANAGER"))).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "select line_id || ':' || target_qty from production_target where target_date = '2026-11-10' order by line_id", String.class))
                .containsExactly("A:2", "B:149", "C:149");
        // 다시 승인해도 두 번 적용하지 않아요
        mvc.perform(post("/api/reassignments/" + id + "/approve").with(user("boss").roles("PLANT_MANAGER"))).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select target_qty from production_target where target_date = '2026-11-10' and line_id = 'B'", Integer.class)).isEqualTo(149);
    }

    @Test
    void r04ApprovePaintReport_switchesPaintRoute() throws Exception {
        // 도장 1호기 고장 보고서 승인 → PT1을 쓰던 A·B라인이 PT2로 (M-09와 같은 경로 전환 + 이력)
        String body = mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":12}"))
                .andExpect(jsonPath("$.kind").value("paint")).andReturn().getResponse().getContentAsString();
        String id = body.replaceAll("(?s).*?\"reportId\":(\\d+).*", "$1");
        mvc.perform(post("/api/reassignments/" + id + "/approve").with(user("boss").roles("PLANT_MANAGER"))).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "select line_id || ':' || current_paint_id from line order by line_id", String.class)).containsExactly("A:PT2", "B:PT2", "C:PT2");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from paint_route_history", Integer.class)).isEqualTo(2);
    }

    @Test
    void r03TargetReport_movesStoppedLinePlanToRunningLines() throws Exception {
        // 11/20 목표 변동 보고서: 고장 건 없이 오늘 목표 기준. C라인 계획정지(S) → C 남은 계획 100개를 A·B에 나눠요. 승인하면 적용
        String today = java.time.LocalDate.now(com.gadong.sfaas.global.util.Kst.ZONE).toString();
        jdbc.batchUpdate(
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values ('C-PR1','C라인 프레스 1호기','PRESS','C',1,true)",
                "insert into downtime_code(code, code_name, is_planned) values ('PL_PM','예방보전',true)",
                "insert into downtime_event(downtime_id, equipment_id, status, code, started_at) values (20,'C-PR1','S','PL_PM','" + today + " 00:00:01')",
                "insert into production_target(target_date, line_id, target_qty, created_at) values "
                        + "('" + today + "','A',100,'" + today + " 00:00:00'), ('" + today + "','B',100,'" + today + " 00:00:00'), "
                        + "('" + today + "','C',100,'" + today + " 00:00:00')",
                "insert into users(user_id, login_id, password_hash, user_name, role) values (2,'boss','x','박공장','PLANT_MANAGER')");
        String body = mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"target\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("target"))
                .andExpect(jsonPath("$.downtimeId").isEmpty())
                .andExpect(jsonPath("$.code").value("TARGET"))
                .andExpect(jsonPath("$.stoppedPlan").value(100))
                .andExpect(jsonPath("$.targetQty").value(300))
                .andExpect(jsonPath("$.details.length()").value(2))
                .andExpect(jsonPath("$.details[0].addQty").value(50))
                .andExpect(jsonPath("$.details[1].addQty").value(50))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll("(?s).*?\"reportId\":(\\d+).*", "$1");
        mvc.perform(get("/api/reassignments").param("from", today).param("to", today))
                .andExpect(jsonPath("$.items[0].code").value("TARGET"))
                .andExpect(jsonPath("$.items[0].approved").value(false));
        mvc.perform(post("/api/reassignments/" + id + "/approve").with(user("boss").roles("PLANT_MANAGER"))).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "select line_id || ':' || target_qty from production_target where target_date = '" + today + "' order by line_id", String.class))
                .containsExactly("A:150", "B:150", "C:0");
    }

    @Test
    void r03AdditionalRequest_excludesLine_andMakesNewReportEachTime() throws Exception {
        // C라인 제외 → 부족 98개를 B가 다 받음 (B 남은 능력 2442). 같은 고장 건이어도 누를 때마다 새 보고서
        String first = mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":10}"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"downtimeId\":10,\"additionalRequest\":\"C라인은 긴급 주문이라 제외하고 납기 우선\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value(org.hamcrest.Matchers.not(Integer.parseInt(first.replaceAll("(?s).*?\"reportId\":(\\d+).*", "$1")))))
                .andExpect(jsonPath("$.lossQty").value(98))
                .andExpect(jsonPath("$.details[0].targetLineId").value("B"))
                .andExpect(jsonPath("$.details[0].addQty").value(98))
                .andExpect(jsonPath("$.details[1].targetLineId").value("C"))
                .andExpect(jsonPath("$.details[1].addQty").value(0))
                .andExpect(jsonPath("$.ai.additionalRequest").value("C라인은 긴급 주문이라 제외하고 납기 우선"))
                .andExpect(jsonPath("$.ai.requestInterpretation.excludedLines[0]").value("C"))
                .andExpect(jsonPath("$.ai.requestInterpretation.priority").value("DUE_DATE"));
        mvc.perform(get("/api/reassignments").param("from", "2000-01-01").param("to", "2100-12-31"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void r03PaintReport_otherPaintTakesOver() throws Exception {
        // PT1을 쓰는 A·B 목표 200, 12시 전 PT1 양품 3 → 부족 197. PT2(C 목표 100, 실적 0): 남은 능력 43200÷4 − 100 = 10700
        mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("paint"))
                .andExpect(jsonPath("$.stoppedPlan").value(200))
                .andExpect(jsonPath("$.stoppedDone").value(3))
                .andExpect(jsonPath("$.lossQty").value(197))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0].targetLineId").value("PT2"))
                .andExpect(jsonPath("$.details[0].spareCapacity").value(10700))
                .andExpect(jsonPath("$.details[0].addQty").value(197))
                .andExpect(jsonPath("$.ai.title").value("도장 1호기 생산계획 수정 제안"));
    }

    @Test
    void r03RejectsNonFaultAndMissing() throws Exception {
        mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":11}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOWNTIME_NOT_FOUND"));
        mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
