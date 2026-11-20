package com.gadong.sfaas.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Swagger(OpenAPI)에 명세서 v3 경로가 있고, 없어진 경로는 없는지 확인해요. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiTest {

    @Autowired MockMvc mvc;

    @Test
    void openApiListsSpecPaths() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/downtime/events']").exists())
                .andExpect(jsonPath("$.paths['/api/planned-stops/{stopId}/end']").exists())
                .andExpect(jsonPath("$.paths['/api/alarms/thresholds/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/reassignments/{reportId}/approve']").exists()); // 11/20 R-04 공장장 승인
    }
}
