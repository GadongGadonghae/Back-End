package com.gadong.sfaas.availability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** 비가동(U-07~U-10)과 상태 타임라인(U-12)을 H2 데이터로 확인해요. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AvailabilityApiTest {

    private static final String DAY = "2026-11-10";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('PRESS','프레스',3), ('PAINTING','도장',5)",
                "insert into line(line_id, line_name) values ('A','A라인'), ('B','B라인')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('A-PR1','프레스A-1','PRESS','A',1,true), ('B-PR1','프레스B-1','PRESS','B',1,true), ('PT1','도장-1','PAINTING',null,1,true)",
                "insert into downtime_code(code, code_name, is_planned) values "
                        + "('UP_BREAKDOWN','장비고장',false), ('UP_ALARM','알람',false), ('UP_DELAY','작업지연',false), ('PL_PM','예방보전',true)",
                "insert into users(user_id, user_name) values (1,'김정비'), (2,'홍길동'), (3,'홍길동')",
                // 1: 40분 고장(조치 완료) / 2: 30초 고장(1분 미만) / 3: 계획정지 / 4: 공용 도장 알람 20분 / 5: 23:50부터 진행 중
                "insert into downtime_event(downtime_id, equipment_id, status, code, started_at, ended_at, action_memo, action_user_id) values "
                        + "(1,'A-PR1','D','UP_BREAKDOWN','2026-11-10 09:00:00','2026-11-10 09:40:00','금형 점검',1), "
                        + "(2,'A-PR1','D','UP_BREAKDOWN','2026-11-10 10:00:00','2026-11-10 10:00:30',null,null), "
                        + "(3,'B-PR1','S','PL_PM','2026-11-10 12:00:00','2026-11-10 13:00:00',null,null), "
                        + "(4,'PT1','D','UP_ALARM','2026-11-10 14:00:00','2026-11-10 14:20:00',null,null), "
                        + "(5,'B-PR1','D','UP_BREAKDOWN','2026-11-10 23:50:00',null,null,null)",
                "insert into equipment_status_history(equipment_id, status, started_at, ended_at, downtime_id) values "
                        + "('A-PR1','R','2026-11-10 08:00:00','2026-11-10 09:00:00',null), ('A-PR1','D','2026-11-10 09:00:00','2026-11-10 09:40:00',1), "
                        + "('A-PR1','R','2026-11-10 09:40:00','2026-11-10 10:00:00',null), ('A-PR1','DC','2026-11-10 10:00:00',null,null)");
    }

    @Test
    void u07SummarizesUnplannedWithinPeriod() throws Exception {
        // 비계획: 2400 + 30 + 1200 + 600(23:50~24:00만) = 4230초 = 70분, 고장 건수는 1분 이상인 1·5번만
        mvc.perform(get("/api/v1/downtime/summary").param("from", DAY).param("to", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMin").value(70))
                .andExpect(jsonPath("$.byReason[0].reasonCode").value("UP_ALARM"))
                .andExpect(jsonPath("$.byReason[0].minutes").value(20))
                .andExpect(jsonPath("$.byReason[1].reasonCode").value("UP_BREAKDOWN"))
                .andExpect(jsonPath("$.byReason[1].minutes").value(50))
                .andExpect(jsonPath("$.byReason[1].count").value(2))
                .andExpect(jsonPath("$.byReason[1].ratio").value(71.6))
                .andExpect(jsonPath("$.byGroup.length()").value(2)) // 공용 PT1은 라인 묶음에서 빠져요
                .andExpect(jsonPath("$.byGroup[0].key").value("A"))
                .andExpect(jsonPath("$.byGroup[0].UP_BREAKDOWN").value(40));
        mvc.perform(get("/api/v1/downtime/summary").param("from", DAY).param("to", DAY).param("groupBy", "process"))
                .andExpect(jsonPath("$.byGroup[0].key").value("PAINTING"));
        mvc.perform(get("/api/v1/downtime/summary").param("from", DAY).param("to", DAY).param("groupBy", "shift"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void u08FiltersEvents() throws Exception {
        mvc.perform(get("/api/v1/downtime/events").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$.totalElements").value(4)) // 30초짜리 2번 제외
                .andExpect(jsonPath("$.content[0].eventId").value(5))
                .andExpect(jsonPath("$.content[0].inProgress").value(true))
                .andExpect(jsonPath("$.content[0].endAt").isEmpty());
        mvc.perform(get("/api/v1/downtime/events").param("from", DAY).param("to", DAY).param("actionUserId", "1"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].minutes").value(40))
                .andExpect(jsonPath("$.content[0].actionUserId").value(1))
                .andExpect(jsonPath("$.content[0].actionUserName").value("김정비"))
                .andExpect(jsonPath("$.content[0].memo").value("금형 점검"))
                .andExpect(jsonPath("$.content[0].startAt").value("2026-11-10T09:00:00+09:00"));
        mvc.perform(get("/api/v1/downtime/events").param("from", DAY).param("to", DAY).param("minMinutes", "0"))
                .andExpect(jsonPath("$.totalElements").value(5));
        mvc.perform(get("/api/v1/downtime/events").param("from", DAY).param("to", DAY).param("inProgress", "true"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/downtime/events").param("from", DAY).param("to", DAY).param("lineId", "A"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void u09CorrectsReasonMemoAndActionBy() throws Exception {
        mvc.perform(patch("/api/v1/downtime/events/2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"UP_DELAY\",\"memo\":\"자재 대기\",\"actionUserId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonCode").value("UP_DELAY"))
                .andExpect(jsonPath("$.actionUserId").value(1))
                .andExpect(jsonPath("$.actionUserName").value("김정비"));
        em.flush();
        mvc.perform(get("/api/v1/downtime/events").param("from", DAY).param("to", DAY).param("minMinutes", "0").param("reasonCode", "UP_DELAY"))
                .andExpect(jsonPath("$.content[0].actionUserName").value("김정비"))
                .andExpect(jsonPath("$.content[0].memo").value("자재 대기"));
        mvc.perform(patch("/api/v1/downtime/events/2").contentType(MediaType.APPLICATION_JSON).content("{\"actionUserId\":999}"))
                .andExpect(status().isBadRequest()); // 없는 사용자
        mvc.perform(patch("/api/v1/downtime/events/2").contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"NOPE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/downtime/events/999").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOWNTIME_NOT_FOUND"));
    }

    @Test
    void u10RanksLongBreakdowns() throws Exception {
        mvc.perform(get("/api/v1/downtime/top").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].equipmentId").value("B-PR1")) // 진행 중이라 가장 길어요
                .andExpect(jsonPath("$[1].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$[1].totalMin").value(40))
                .andExpect(jsonPath("$[1].count").value(1));
        mvc.perform(get("/api/v1/downtime/top").param("from", DAY).param("to", DAY).param("limit", "1"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void u12StatusTimelineWithReason() throws Exception {
        mvc.perform(get("/api/v1/equipments/A-PR1/status-history").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].status").value("R"))
                .andExpect(jsonPath("$[0].reasonCode").isEmpty())
                .andExpect(jsonPath("$[1].status").value("D"))
                .andExpect(jsonPath("$[1].reasonCode").value("UP_BREAKDOWN"))
                .andExpect(jsonPath("$[3].status").value("DC"))
                .andExpect(jsonPath("$[3].reasonCode").isEmpty())
                .andExpect(jsonPath("$[3].endAt").isEmpty());
        mvc.perform(get("/api/v1/equipments/X-PR9/status-history").param("from", DAY).param("to", DAY))
                .andExpect(status().isNotFound());
    }
}
