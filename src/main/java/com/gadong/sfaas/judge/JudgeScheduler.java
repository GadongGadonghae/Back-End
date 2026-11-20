package com.gadong.sfaas.judge;

import com.gadong.sfaas.judge.service.ProcessJudgeService;
import com.gadong.sfaas.judge.service.StatusJudgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1초마다 공정 판정 → 상태 판정 순서로 불러요. 판정 규칙은 각 Service에만 있어요. 테스트는 sfaas.judge.enabled=false로 꺼요 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "sfaas.judge.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class JudgeScheduler {
    private final ProcessJudgeService processJudgeService;
    private final StatusJudgeService statusJudgeService;

    @Scheduled(fixedDelay = 1000)
    public void run() {
        processJudgeService.judge();
        statusJudgeService.judge();
    }
}
