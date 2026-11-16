
package com.gadong.sfaas.auth.dto;

public record LoginResponse(
    String accessToken,
    String userName,
    String role
) {
}
