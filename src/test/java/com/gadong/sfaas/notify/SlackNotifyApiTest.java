package com.gadong.sfaas.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/**
 * N-01: 보고서 → 슬랙 메시지. 실제 슬랙 대신 웹훅 호출을 가로채 보낸 내용을 확인해요.
 * 테스트에서는 ai.report.mock 기본값(true)이라 요약은 템플릿 문장이에요.
 */
@SpringBootTest(properties = {
        "slack.webhook-url=https://hooks.slack.test/services/T/B/X",
        "slack.app-base-url=http://192.168.0.50/"})
@WithMockUser(username = "u1", roles = "PRODUCTION_MANAGER")
@AutoConfigureMockMvc
@Transactional
class SlackNotifyApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean SlackWebhookClient webhook;

    @BeforeEach
    void seed() {
        SlackFixture.seed(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsEquipmentReportAndSummary() throws Exception {
        String id = SlackFixture.createReport(mvc);

        mvc.perform(post("/api/reassignments/" + id + "/slack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(Long.parseLong(id)))
                .andExpect(jsonPath("$.mock").value(false))
                .andExpect(jsonPath("$.summaryModel").value("template"))
                .andExpect(jsonPath("$.summary.length()").value(3))
                .andExpect(jsonPath("$.summary[1]").value("부족 98개를 B라인 +49개, C라인 +49개로 나눠 맡기는 안입니다."))
                .andExpect(jsonPath("$.summary[2]").value("재배정하면 오늘 목표를 맞출 수 있습니다."));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webhook).post(captor.capture());
        String json = objectMapper.writeValueAsString(captor.getValue());

        // 고장 장비 정보
        assertThat(json).contains("A라인 프레스 1호기 고장 · 계획수정 보고서");
        assertThat(json).contains("A라인 · 프레스");
        assertThat(json).contains("장비고장");
        assertThat(json).contains("해제 11-10 12:40 (40분)");
        // 사용자 문장 속 <!channel> 은 이스케이프돼서 채널 전체 알림이 울리지 않아요
        assertThat(json).doesNotContain("<!channel>").contains("&lt;!channel&gt;");
        // 보고서: 문서번호·작성자·재배정 표
        assertThat(json).contains("AI-RPT-").contains("김관리").contains("B라인").contains("+49");
        // 요약
        assertThat(json).contains("부족 98개를 B라인 +49개, C라인 +49개로 나눠 맡기는 안입니다.");
        // 보고서 보기 버튼 (끝의 / 는 하나로)
        assertThat(json).contains("http://192.168.0.50/reports/replan/" + id);
    }

    @Test
    void slackRejects_returns502WithReason() throws Exception {
        String id = SlackFixture.createReport(mvc);
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null,
                "no_service".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).when(webhook).post(any());

        mvc.perform(post("/api/reassignments/" + id + "/slack"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SLACK_SEND_FAILED"))
                .andExpect(jsonPath("$.message").value("슬랙 전송에 실패했습니다 (404 no_service)"));
    }

    @Test
    void unknownReport_returns404() throws Exception {
        mvc.perform(post("/api/reassignments/999999/slack"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REASSIGNMENT_NOT_FOUND"));
    }
}
