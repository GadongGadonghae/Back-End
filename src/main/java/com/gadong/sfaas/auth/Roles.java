package com.gadong.sfaas.auth;

import java.util.List;
import java.util.Map;

/**
 * 계정 권한 (11/20 팀 결정). users.role에 이 코드를 저장하고, 로그인 토큰에 담겨 ROLE_코드로 검사해요.
 * <pre>
 * 기능                         공장장  생산관리 팀장  라인 담당자
 * 1 기록 열람 (조회 API 전부)      O        O           O
 * 2 알람 이력 조치 작성 (U-09)     -        O           O
 * 3 생산 목표 설정 (G-03)          O        -           -
 * 4 관리값 기준 설정 (M-11)        -        O           -
 * 5 사용자 관리 (C-02·C-03)        O        -           -
 * 6 AI 리포트 작성 (R-03)          -        O           -
 *   AI 리포트 승인·결재 (R-04)     O        -           -
 *   보고서 슬랙 전송 (N-01)         O        O           -
 * 캠퍼스장(CAMPUS, 11/20): 기록 열람만 (보기 전용, /mes 새 프론트에서 보고서 확인)
 * </pre>
 * 컨트롤러에 @PreAuthorize("hasRole('…')")로 걸어요. 권한이 없으면 403 FORBIDDEN.
 */
public final class Roles {

    public static final String PLANT = "PLANT_HEAD";           // 공장장
    public static final String PRODUCTION = "PROD_LEAD"; // 생산관리 팀장
    public static final String LINE = "LINE_WORKER";             // 라인 담당자
    public static final String CAMPUS = "CAMPUS";            // 캠퍼스장 (보기 전용)

    public static final Map<String, String> LABEL = Map.of(PLANT, "공장장", PRODUCTION, "생산관리 팀장", LINE, "라인 담당자", CAMPUS, "캠퍼스장");
    public static final List<String> ALL = List.of(PLANT, PRODUCTION, LINE, CAMPUS);

    private Roles() {
    }
}
