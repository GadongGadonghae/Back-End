package com.gadong.sfaas.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 슬랙 알림 설정 (application.yml 의 slack.*).
 *
 * @param webhookUrl     슬랙 Incoming Webhook 주소. 비밀값이라 application-local.yml 또는 환경 변수 SLACK_WEBHOOK_URL 에만 넣어요
 * @param mock           true 면 실제로 보내지 않고 보낼 메시지(JSON)를 로그에만 남겨요 (웹훅 없이 개발할 때)
 * @param timeoutSeconds 슬랙 호출 제한 시간
 * @param appBaseUrl     메시지의 "보고서 보기" 버튼 주소 앞부분. 예: http://192.168.203.229 . 비우면 버튼을 넣지 않아요.
 *                       auto면 보낼 때 이 컴퓨터 주소를 찾아 http://주소:5173 (11/22)
 */
@ConfigurationProperties(prefix = "slack")
public record SlackProperties(
        @DefaultValue("") String webhookUrl,
        @DefaultValue("false") boolean mock,
        @DefaultValue("10") int timeoutSeconds,
        @DefaultValue("") String appBaseUrl
) {
    public boolean configured() {
        return webhookUrl != null && webhookUrl.startsWith("https://");
    }
}
