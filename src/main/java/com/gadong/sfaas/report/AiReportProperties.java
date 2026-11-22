package com.gadong.sfaas.report;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * AI 보고서 설정 (application.yml 의 ai.report.*).
 *
 * @param provider            anthropic(Claude API, 기본) 또는 openai(OpenAI 호환 API: Gemini 등, baseUrl 사용)
 * @param effort              Claude 생각 깊이 low·medium·high (provider=anthropic 일 때만)
 *
 * @param allocatableStatuses 재배정 후보로 쓸 라인 상태 (명세서 RSID). 기본 R·DC·I.
 *                            DC(통신 끊김)는 가동으로 계산하고, I(대기)는 재공이 없어 능력이 남는 상태라 포함한다.
 *                            D(고장)·S(계획정지)는 제외. I 포함 여부는 팀 확정 전까지 설정으로 바꿀 수 있다.
 */
@ConfigurationProperties(prefix = "ai.report")
public record AiReportProperties(
        @DefaultValue("true") boolean mock,                       // 설정이 없으면 시연 모드 (외부 LLM 호출 안 함)
        @DefaultValue("anthropic") String provider,
        @DefaultValue("https://api.openai.com/v1") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("claude-opus-5-5") String model,
        @DefaultValue("low") String effort,
        @DefaultValue("30") int timeoutSeconds,
        @DefaultValue({"R", "DC", "I"}) List<String> allocatableStatuses,
        @DefaultValue List<String> fallbackModels,                // 첫 모델이 안 될 때 차례로 부를 모델 (11/22, OpenAI 호환만)
        @DefaultValue("0") int firstTimeoutSeconds                // 예비 모델이 있을 때 앞 모델을 기다리는 시간 (0 = timeoutSeconds)
) {
    boolean anthropic() {
        return provider == null || provider.isBlank() || "anthropic".equalsIgnoreCase(provider.trim());
    }
}
