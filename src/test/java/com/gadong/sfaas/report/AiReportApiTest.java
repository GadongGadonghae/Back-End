package com.gadong.sfaas.report;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** AI 계획수정 보고서 생성 API (POST /api/ai-reports/generate). 기본 설정은 시연 모드(mock)라 외부 LLM을 부르지 않아요. */
@SpringBootTest
@WithMockUser(username = "testuser", roles = "MANAGER") // API는 로그인 필요 (SecurityConfig)
@AutoConfigureMockMvc
class AiReportApiTest {

    private static final String URL = "/api/ai-reports/generate";
    private static final String BODY = """
            {"downtimeId":5001,"lineId":"B","equipmentId":"B-PR3","equipmentName":"B라인 프레스 3호기",
             "faultReason":"압력 차(좌-우) 상한 초과 (13.4 / 상한 12.5)","faultStartedAt":"2026-11-16T14:20:12+09:00",
             "targetQty":200,"completedQty":108,"dueQty":200,"dueAt":"2026-11-16 24:00",
             "lineConditions":[{"lineId":"A","status":"R","availableCapacity":1962,"currentPlan":69,"constraint":""},
                               {"lineId":"B","status":"D","availableCapacity":0,"currentPlan":92,"constraint":"설비 고장"}],
             "operatorNote":"","additionalRequest":""}
            """;

    @Autowired MockMvc mvc;

    @Test
    void 정상_요청은_201과_KST_시각을_돌려준다() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.generatedAt", endsWith("+09:00")))
                .andExpect(jsonPath("$.calculatedShortage").value(92))
                .andExpect(jsonPath("$.allocations[0].lineId").value("A"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.fallbackReason").doesNotExist());
    }

    @Test
    void 필수값이_빠지면_공통_오류_형식으로_400() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithAnonymousUser
    void 로그인하지_않으면_401() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
