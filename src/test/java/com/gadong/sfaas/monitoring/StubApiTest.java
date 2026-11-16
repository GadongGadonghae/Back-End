package com.gadong.sfaas.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 모니터링·가동률 stub API가 명세서 v2 모양으로 응답하는지 확인해요. (서비스를 구현하면 이 테스트를 실제 값 기준으로 바꿔요) */
@SpringBootTest
@AutoConfigureMockMvc
class StubApiTest {

    @Autowired MockMvc mvc;

    @Test
    void m01StatusHasCommonFields() throws Exception {
        mvc.perform(get("/api/v1/monitoring/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$[0].status").value("D"))
                .andExpect(jsonPath("$[0].reasonCode").value("UP_BREAKDOWN"));
    }

    @Test
    void m07CountsFollowFormula() throws Exception {
        mvc.perform(get("/api/v1/production/counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1200))
                .andExpect(jsonPath("$.ok").value(1142))
                .andExpect(jsonPath("$.byProcess[0].process").value("PRESS"));
    }

    @Test
    void m02LineSummaryUsesUppercaseKeys() throws Exception {
        mvc.perform(get("/api/v1/monitoring/lines/summary"))
                .andExpect(jsonPath("$[0].R").value(5))
                .andExpect(jsonPath("$[0].DC").value(0))
                .andExpect(jsonPath("$[0].worstStatus").value("I"));
    }

    @Test
    void m10ThresholdsAreDistinguishedByEquipment() throws Exception {
        mvc.perform(get("/api/v1/alarms/thresholds").param("equipmentId", "C-WD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].equipmentId").value("C-WD"))
                .andExpect(jsonPath("$[0].process").value("WELDING"));
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
    void r03Approve() throws Exception {
        mvc.perform(patch("/api/v1/reassignments/1/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void openApiListsAllStubOperations() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/downtime/events']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/planned-stops']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/alarms/thresholds/{id}']").exists());
    }
}
