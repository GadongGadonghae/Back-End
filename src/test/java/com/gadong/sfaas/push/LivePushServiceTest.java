package com.gadong.sfaas.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

/** W-05 물류 · W-04 공정 값: 구독한 화면이 있을 때만 보내고, 구독을 끊으면 더 계산하지 않아요. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LivePushServiceTest {

    @LocalServerPort int port;
    @Autowired LivePushService livePushService;
    @Autowired LiveSubscriptions subscriptions;

    private StompSession connect() throws Exception {
        return new WebSocketStompClient(new StandardWebSocketClient())
                .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private static StompFrameHandler into(CompletableFuture<String> received) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                received.complete(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        };
    }

    /** 구독 신호는 서버에 비동기로 닿아요 → 등록될 때까지 잠깐 기다려요 */
    private void awaitSubscribed(String destination, boolean expected) throws InterruptedException {
        for (int i = 0; i < 50 && subscriptions.has(destination) != expected; i++) Thread.sleep(50);
        assertThat(subscriptions.has(destination)).isEqualTo(expected);
    }

    @Test
    void flow_pushedOnlyWhileSubscribed() throws Exception {
        StompSession session = connect();
        CompletableFuture<String> received = new CompletableFuture<>();
        StompSession.Subscription sub = session.subscribe(PushService.FLOW, into(received));
        awaitSubscribed(PushService.FLOW, true);

        livePushService.pushSubscribed();

        String json = received.get(5, TimeUnit.SECONDS);
        assertThat(json).contains("\"products\"").contains("\"coilStockQty\"").contains("\"at\"");

        sub.unsubscribe();
        awaitSubscribed(PushService.FLOW, false);
        session.disconnect();
    }

    @Test
    void metricsSubscription_tracksEquipmentId() throws Exception {
        StompSession session = connect();
        session.subscribe(PushService.METRICS_PREFIX + "A-PR1", into(new CompletableFuture<>()));
        awaitSubscribed(PushService.METRICS_PREFIX + "A-PR1", true);

        assertThat(subscriptions.suffixes(PushService.METRICS_PREFIX)).contains("A-PR1");
        livePushService.pushSubscribed(); // 처음 구독된 설비는 시각만 기억하고 보내지 않아요 (예외 없이 지나가야 함)

        session.disconnect();
        awaitSubscribed(PushService.METRICS_PREFIX + "A-PR1", false); // 연결이 끊기면 구독도 지워짐
    }
}
