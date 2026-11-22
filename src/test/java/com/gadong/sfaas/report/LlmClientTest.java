package com.gadong.sfaas.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 첫 모델이 붐비거나 느리면 예비 모델로 (11/22) */
class LlmClientTest {

    private final List<String> called = new CopyOnWriteArrayList<>();
    private volatile Set<String> busy = Set.of();
    private volatile Set<String> slow = Set.of();
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", ex -> {
            String model = JsonMapper.builder().build().readTree(ex.getRequestBody()).path("model").asText();
            called.add(model);
            if (slow.contains(model)) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] body = (busy.contains(model) ? "{\"error\":{\"code\":503}}"
                    : "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"ok\\\":true}\\n```\"}}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(busy.contains(model) ? 503 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LlmClient client(List<String> fallbacks) {
        AiReportProperties p = new AiReportProperties(false, "openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key",
                "first", "low", 10, List.of("R"), fallbacks, 1);
        return new LlmClient(p, JsonMapper.builder().build(), RestClient.builder());
    }

    @Test
    void 첫_모델이_답하면_그대로_쓴다() {
        LlmClient.Reply r = client(List.of("second")).chat("s", "u");
        assertThat(r).isEqualTo(new LlmClient.Reply("{\"ok\":true}", "first"));
        assertThat(called).containsExactly("first");
    }

    @Test
    void 붐비면_한번_더_부른_뒤_예비_모델로_가고_잠시_첫_모델을_건너뛴다() {
        busy = Set.of("first");
        LlmClient c = client(List.of("second", "third"));
        assertThat(c.chat("s", "u").model()).isEqualTo("second");
        assertThat(called).containsExactly("first", "first", "second");
        assertThat(c.chat("s", "u").model()).isEqualTo("second");
        assertThat(called).containsExactly("first", "first", "second", "second");
    }

    @Test
    void 느리면_기다리지_않고_예비_모델로_간다() {
        slow = Set.of("first");
        assertThat(client(List.of("second")).chat("s", "u").model()).isEqualTo("second");
        assertThat(called).containsExactly("first", "second");
    }

    @Test
    void 모두_붐비면_이유를_알린다() {
        busy = Set.of("first", "second");
        assertThatThrownBy(() -> client(List.of("second")).chat("s", "u"))
                .isInstanceOf(AiReportService.LlmUnavailableException.class).hasMessageContaining("바빠").hasMessageNotContaining("test-key");
    }
}
