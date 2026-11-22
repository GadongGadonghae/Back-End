package com.gadong.sfaas.notify;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** 웹훅 주소가 없으면 LLM을 부르기 전에 503 으로 알려 줘요 */
@SpringBootTest
@WithMockUser(username = "u1", roles = "PROD_LEAD")
@AutoConfigureMockMvc
@Transactional
class SlackNotConfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        SlackFixture.seed(jdbc);
    }

    @Test
    void withoutWebhook_returns503() throws Exception {
        String id = SlackFixture.createReport(mvc);
        mvc.perform(post("/api/reassignments/" + id + "/slack"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SLACK_NOT_CONFIGURED"));
    }

    /** 라인 담당자·캠퍼스장은 보낼 수 없어요 (11/22) */
    @Test
    @WithMockUser(username = "u2", roles = {"LINE_WORKER", "CAMPUS"})
    void lineWorkerAndCampus_cannotSend() throws Exception {
        mvc.perform(post("/api/reassignments/1/slack")).andExpect(status().isForbidden());
    }
}
