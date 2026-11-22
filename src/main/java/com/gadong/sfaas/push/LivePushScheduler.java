package com.gadong.sfaas.push;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1초마다 W-04 · W-05를 보내요 (구독한 화면이 있을 때만 계산). 테스트는 sfaas.push.enabled=false로 꺼요 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "sfaas.push.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class LivePushScheduler {
    private final LivePushService livePushService;

    @Scheduled(fixedDelay = 1000)
    public void run() {
        livePushService.pushSubscribed();
    }
}
