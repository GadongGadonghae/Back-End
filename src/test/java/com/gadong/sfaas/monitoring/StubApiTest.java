package com.gadong.sfaas.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 아직 stub인 API가 명세서 v2 모양으로 응답하는지 확인해요. (서비스를 구현하면 그 API 테스트로 옮겨요) */
@SpringBootTest
@AutoConfigureMockMvc
class StubApiTest {

    @Autowired MockMvc mvc;

    @Test
    void m07CountsFollowFormula() throws Exception {
        mvc.perform(get("/api/v1/production/counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1200))
                .andExpect(jsonPath("$.ok").value(1142))
                .andExpect(jsonPath("$.byProcess[0].process").value("PRESS"));
    }

    @Test
    void u01RequiresPeriodAndLeavesUnknownNull() throws Exception {
        mvc.perform(get("/api/v1/utilization/summary"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/utilization/summary").param("from", "2026-11-17").param("to", "2026-11-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actual").value(91.4))
                .andExpect(jsonPath("$.lossQty").doesNotExist());
    }

    @Test
    void s01s02FollowSpecV02Shape() throws Exception {
        mvc.perform(get("/api/v1/planned-stops").param("from", "2026-11-17").param("to", "2026-11-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].planId").value(1))
                .andExpect(jsonPath("$[0].endAt").exists());
        mvc.perform(post("/api/v1/planned-stops").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"A\",\"reasonCode\":\"PL_PM\",\"startAt\":\"2026-11-18T12:00:00+09:00\",\"endAt\":\"2026-11-18T13:00:00+09:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planId").value(2));
    }

    @Test
    void openApiListsSpecPaths() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/downtime/events']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/planned-stops/{stopId}/end']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/alarms/thresholds/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/reassignments/{reportId}/approve']").exists());
    }
}
