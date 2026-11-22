package com.gadong.sfaas.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI 호환 LLM(Gemini) 부르기 (11/22). AI 보고서 · 원인 추적 · 추가 주문 해석 · 슬랙 요약이 모두 이걸 써요.
 * <ol>
 *   <li>첫 모델(ai.report.model)을 first-timeout-seconds까지 기다려요. 503(붐빔)·429(한도)면 1초 뒤 한 번 더</li>
 *   <li>그래도 안 되면 예비 모델(fallback-models)을 차례로 불러요. 마지막 모델은 timeout-seconds까지 기다리고 두 번 더 시도</li>
 *   <li>첫 모델이 실패하면 2분 동안은 바로 예비 모델로 (보고서 하나에 LLM을 2~3번 불러서 매번 기다리지 않게)</li>
 * </ol>
 * 모두 실패하면 LlmUnavailableException → 부른 쪽이 템플릿 문장으로 대신해요.
 */
@Component
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final long SKIP_MS = 120_000;

    /** 답한 글과 실제로 답한 모델 */
    public record Reply(String text, String model) {}

    private final AiReportProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient quick;
    private final RestClient patient;
    /** 이 시각(ms)까지는 첫 모델을 건너뛰어요 */
    private volatile long skipFirstUntil;

    public LlmClient(AiReportProperties properties, ObjectMapper objectMapper, RestClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        int full = properties.timeoutSeconds() > 0 ? properties.timeoutSeconds() : 30;
        int first = properties.firstTimeoutSeconds() > 0 ? Math.min(full, properties.firstTimeoutSeconds()) : full;
        this.quick = client(builder, first);
        this.patient = client(builder, full);
    }

    private RestClient client(RestClient.Builder builder, int seconds) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(seconds));
        f.setReadTimeout(Duration.ofSeconds(seconds));
        return builder.clone().baseUrl(properties.baseUrl()).requestFactory(f).build();
    }

    /** 시스템 지시와 사용자 내용을 보내고 JSON 본문(문자열)을 받아요. ```json 으로 감싸 오면 벗겨 내요 */
    public Reply chat(String systemPrompt, String userContent) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new AiReportService.LlmUnavailableException("AI_REPORT_API_KEY가 설정되지 않았습니다", null);
        }
        List<String> models = models();
        Exception failed = null;
        for (int i = 0; i < models.size(); i++) {
            boolean last = i == models.size() - 1;
            if (i == 0 && !last && System.currentTimeMillis() < skipFirstUntil) continue;
            String model = models.get(i);
            try {
                return new Reply(call(last ? patient : quick, model, last ? 2 : 1, systemPrompt, userContent), model);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiReportService.LlmUnavailableException("LLM 호출이 중단되었습니다", e);
            } catch (Exception e) {
                failed = e;
                if (i == 0 && !last) skipFirstUntil = System.currentTimeMillis() + SKIP_MS;
                if (!last) log.warn("LLM {} 실패({}), {}로 다시 부릅니다", model, reason(e), models.get(i + 1));
            }
        }
        if (busy(failed)) {
            throw new AiReportService.LlmUnavailableException("LLM 서버가 바빠 응답하지 않았습니다 ("
                    + ((RestClientResponseException) failed).getStatusCode().value() + "), 잠시 뒤 다시 만들어 주세요", failed);
        }
        // 키·응답 본문이 섞일 수 있어 원인 메시지는 로그에만 남기고 화면에는 일반 문구만 보낸다
        throw new AiReportService.LlmUnavailableException("LLM 호출에 실패했거나 " + properties.timeoutSeconds() + "초 안에 응답하지 않았습니다", failed);
    }

    private List<String> models() {
        List<String> out = new ArrayList<>(List.of(properties.model()));
        if (properties.fallbackModels() != null) {
            properties.fallbackModels().stream().map(String::trim).filter(m -> !m.isEmpty() && !out.contains(m)).forEach(out::add);
        }
        return out;
    }

    private String call(RestClient client, String model, int retries, String systemPrompt, String userContent) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "reasoning_effort", effort(properties.effort()),   // Gemini 생각 깊이 (기본 low: 사실 정리라 빠르게)
                "temperature", 0.2,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)));
        for (int attempt = 0; ; attempt++) {
            try {
                String raw = client.post()
                        .uri("/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                String text = stripFence(objectMapper.readTree(raw).path("choices").path(0).path("message").path("content").asText());
                if (text.isBlank()) throw new IllegalStateException("빈 응답");
                return text;
            } catch (HttpServerErrorException.ServiceUnavailable | HttpClientErrorException.TooManyRequests e) {
                if (attempt >= retries) throw e;
                Thread.sleep(1000L * (attempt + 1));
            }
        }
    }

    private static boolean busy(Exception e) {
        return e instanceof HttpServerErrorException.ServiceUnavailable || e instanceof HttpClientErrorException.TooManyRequests;
    }

    private static String reason(Exception e) {
        return e instanceof RestClientResponseException r ? "HTTP " + r.getStatusCode().value() : e.getClass().getSimpleName();
    }

    static String stripFence(String text) {
        return text.trim().replaceAll("(?s)^```(?:json)?\\s*(.*?)\\s*```$", "$1");
    }

    /** Gemini(OpenAI 호환) reasoning_effort: low · medium · high 만 받아요. 그 밖의 값은 low */
    private static String effort(String effort) {
        String e = effort == null ? "" : effort.trim().toLowerCase();
        return e.equals("medium") || e.equals("high") ? e : "low";
    }
}
