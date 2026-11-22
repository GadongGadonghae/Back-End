package com.gadong.sfaas.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/** /ws 는 허용한 화면 주소(Origin)에서만 붙어요: 이 PC · 사내망 192.168.0.x · 192.168.203.x 의 아무 포트. 그 밖의 주소는 거절(403)해요. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketOriginTest {

    @LocalServerPort int port;

    private WebSocketSession connect(String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        return new StandardWebSocketClient()
                .execute(new AbstractWebSocketHandler() {}, headers, URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:5173",       // 개발 서버
            "http://localhost:4173",       // 미리보기
            "http://127.0.0.1:5173",
            "http://192.168.0.10:5173",    // 사내망 IP로 개발 서버 접속
            "http://192.168.0.10:4173",
            "http://192.168.0.10",         // nginx 80 배포 (포트 없음)
            "http://192.168.203.229:5173", // 서버 PC 대역
            "http://192.168.203.229"
    })
    void allowedOrigin_connects(String origin) throws Exception {
        WebSocketSession session = connect(origin);
        assertThat(session.isOpen()).isTrue();
        session.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://192.168.1.10:5173", "http://10.0.0.5:5173", "https://example.com"})
    void otherOrigin_rejected(String origin) {
        assertThatThrownBy(() -> connect(origin)).hasStackTraceContaining("403");
    }
}
