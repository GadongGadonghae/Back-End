package com.gadong.sfaas.report;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 고장 원인 추적 (계획수정 보고서 "원인 추적" 칸). 설명 가능한 AI 보고서의 근거 묶음이에요.
 * <ul>
 *   <li>evidence: 코드가 DB에서 모은 사실. 번호 E1, E2 … 로 문장이 가리켜요</li>
 *   <li>causes: 원인 후보와 근거 강도. 점수는 AI가 아니라 규칙으로 매겨요 (확률이 아님)</li>
 *   <li>sentences: AI(또는 템플릿)가 쓴 문장. 문장마다 근거 번호를 달고, 코드가 번호·숫자를 검증해요</li>
 * </ul>
 *
 * @param verifiedCount  검증을 통과한 문장 수
 * @param model          문장을 쓴 모델. 템플릿이면 "mock-template"
 * @param mock           true면 템플릿 문장 (시연 모드이거나 LLM 실패)
 * @param fallbackReason LLM을 쓰려다 실패해 템플릿으로 대신한 이유
 */
public record CauseTrace(
        OffsetDateTime windowFrom,
        OffsetDateTime windowTo,
        List<Evidence> evidence,
        List<Cause> causes,
        List<Sentence> sentences,
        int verifiedCount,
        String model,
        boolean mock,
        String fallbackReason
) {
    /**
     * 근거 하나.
     *
     * @param type   FAULT 고장 건 · ALARM 직전 알람 · TREND 직전 공정값 · QUALITY 직전 공정 불량 · STATE 직전 상태 변화 · HISTORY 과거 고장
     * @param source 원본 위치 (표 이름#행 번호 또는 조회 조건)
     */
    public record Evidence(String id, String type, String title, String detail, OffsetDateTime at, String source) {
    }

    /**
     * 원인 후보.
     *
     * @param item  관련 관리 항목 (예: pressureDiff). 항목과 관계없는 후보면 null
     * @param grade HIGH(70점 이상) · MEDIUM(40~69) · LOW(40 미만)
     */
    public record Cause(String id, String label, String item, int score, String grade, List<ScorePart> parts, List<String> evidence) {
    }

    /** 점수 내역 한 줄: 직접 신호 40 · 시간 근접 20 · 과거 사례 30 · 데이터 완전성 10 */
    public record ScorePart(String label, int points, int max, String note) {
    }

    /**
     * 보고서 문장 한 줄.
     *
     * @param kind     fact 사실 · inference 추정 · action 권장 점검
     * @param verified 근거 번호가 있고, 문장의 숫자가 인용한 근거에 있으면 true
     * @param issue    검증에 실패한 이유 (통과면 null)
     */
    public record Sentence(String text, String kind, List<String> evidence, boolean verified, String issue) {
    }
}
