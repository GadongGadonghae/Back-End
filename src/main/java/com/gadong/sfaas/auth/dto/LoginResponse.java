
package com.gadong.sfaas.auth.dto;

public record LoginResponse(
    String accessToken,
    String userName,
    String role,
    /** 라인 담당자의 담당 라인 (그 밖 권한은 빈 목록, v0.3.15) */
    java.util.List<String> lines
) {
}
