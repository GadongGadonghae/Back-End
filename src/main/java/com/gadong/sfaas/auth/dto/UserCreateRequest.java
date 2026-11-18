
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
    String memo
) {
}
