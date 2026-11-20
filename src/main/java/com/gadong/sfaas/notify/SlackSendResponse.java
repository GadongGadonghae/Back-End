package com.gadong.sfaas.notify;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 슬랙 전송 결과 (N-01). 기록은 DB에 남기지 않아요.
 *
 * @param mock                  true 면 실제로 보내지 않고 로그에만 남김 (slack.mock=true)
 * @param summary               메시지에 넣은 요약 문장
 * @param summaryModel          요약에 쓴 모델. 템플릿이면 "template"
 * @param summaryFallbackReason LLM 요약에 실패해 템플릿으로 대신한 이유. 대신하지 않았으면 null
 */
public record SlackSendResponse(long reportId, boolean mock, OffsetDateTime sentAt, List<String> summary,
                                String summaryModel, String summaryFallbackReason) {
}
