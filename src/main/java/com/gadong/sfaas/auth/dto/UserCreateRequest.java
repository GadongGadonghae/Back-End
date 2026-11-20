
package com.gadong.sfaas.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(
    @NotBlank
    @Size(max = 30)
    String loginId,

    @NotBlank
    String password,

    @NotBlank
    @Size(max = 20)
    String userName,

    @NotBlank
    @Size(max = 20)
    String role,

    @Size(max = 100)
    String email,

    @Size(max = 200)
    String memo,

    /** 라인 담당자의 담당 라인 (예: ["A","B"]). 선택 (v0.3.15) */
    java.util.List<String> lines
) {
}
