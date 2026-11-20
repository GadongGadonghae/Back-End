package com.gadong.sfaas.notify;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 슬랙 Incoming Webhook 호출만 맡아요. 실패하면 예외를 던져요 */
@Component
public class SlackWebhookClient {
    private final SlackProperties properties;
    private final RestClient restClient;

    public SlackWebhookClient(SlackProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds() > 0 ? properties.timeoutSeconds() : 10);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    /** 슬랙은 성공하면 200 "ok", 실패하면 4xx와 이유 문자열(invalid_blocks 등)을 줘요 */
    public void post(Map<String, Object> payload) {
        restClient.post()
                .uri(properties.webhookUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}
