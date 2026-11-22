package com.gadong.sfaas.notify;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림 (슬랙)")
@RestController
@RequestMapping("/api")
public class SlackController {
    private final SlackNotifyService service;

    public SlackController(SlackNotifyService service) {
        this.service = service;
    }

    @Operation(summary = "N-01 계획수정 보고서를 슬랙으로 보내기",
            description = "고장 장비 정보 + 보고서(재배정 표·조치·위험) + 보고서를 LLM으로 요약한 문장을 슬랙 웹훅 1건으로 보내요. "
                    + "LLM 요약이 실패하면 템플릿 문장으로 대신 보내요(summaryFallbackReason). "
                    + "공장장·생산관리 팀장만. 웹훅이 없으면 503 SLACK_NOT_CONFIGURED, 슬랙이 거절하면 502 SLACK_SEND_FAILED. 보낸 기록은 남기지 않아요.")
    @PreAuthorize("hasAnyRole('PLANT_HEAD', 'PROD_LEAD')") // 팀 방에 바로 올라가서 공장장·생산관리 팀장만 (11/22)
    @PostMapping("/reassignments/{reportId}/slack")
    public SlackSendResponse send(@PathVariable long reportId) {
        return service.send(reportId);
    }
}
