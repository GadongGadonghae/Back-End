package com.gadong.sfaas.report.flow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** 보고서 결재·전송 API (R-10 ~ R-14, M-01) 요청·응답 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * R-10. kind = DAILY · REPLAN. targetKey: DAILY = 날짜(yyyy-MM-dd), REPLAN = 비가동 eventId 또는 "TARGET"(오늘 생산 목표 변경).
     * additionalRequest = 사람이 쓴 자연어(500자, REPLAN에서 Gemini가 해석). content = DAILY 보고서 내용(지금은 화면이 계산한 값)
     */
    public record ReportCreateRequest(String kind, String targetKey, String additionalRequest, JsonNode content) {}

    /** R-15 결재 요청 본문 (선택): 그 순간 화면 문서 */
    public record ReportRequestBody(JsonNode content) {}

    public record Person(Integer userId, String userName) {}

    /** 보고서 객체 (목록·MES에서는 content = null). replanReportId = 계획수정 보고서 번호(R-02로 열어요) */
    public record ReportResponse(long reportId, String kind, String targetKey, String title, String status,
                                 String campusId, int factoryId, Long replanReportId,
                                 Person writtenBy, OffsetDateTime writtenAt, Person approvedBy, OffsetDateTime approvedAt,
                                 Person sentBy, OffsetDateTime sentAt, JsonNode content) {}
}
