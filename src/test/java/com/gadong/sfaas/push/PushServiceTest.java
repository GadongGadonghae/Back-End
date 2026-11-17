package com.gadong.sfaas.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.EquipmentStatusResponse;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** /ws 에 STOMP로 붙어 /topic/status 를 구독하고, pushStatus 메시지가 +09:00 시각으로 오는지 확인해요. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushServiceTest {

    @LocalServerPort int port;
    @Autowired PushService pushService;

    @Test
    void pushStatus_reachesSubscriber() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        CompletableFuture<String> received = new CompletableFuture<>();
        session.subscribe("/topic/status", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                received.complete(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });

        var status = new EquipmentStatusResponse("C-WD", "C", "WELDING", "D",
                Kst.of(LocalDateTime.of(2026, 11, 18, 9, 31, 10)), "UP_BREAKDOWN", Map.of("current", 15.3));
        // SUBSCRIBE가 브로커에 등록되기 전에 보낸 메시지는 버려지므로, 받을 때까지 몇 번 보내요
        String json = null;
        for (int i = 0; i < 50 && json == null; i++) {
            pushService.pushStatus(status);
            try { json = received.get(100, TimeUnit.MILLISECONDS); } catch (TimeoutException ignored) {}
        }
        session.disconnect();
        client.stop();

        assertThat(json).isNotNull()
                .contains("\"equipmentId\":\"C-WD\"")
                .contains("\"status\":\"D\"")
                .contains("\"since\":\"2026-11-18T09:31:10+09:00\"");
    }
}
