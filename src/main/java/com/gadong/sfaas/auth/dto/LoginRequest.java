
package com.gadong.sfaas.auth.dto;

public record LoginRequest(
    String loginId,
    String password
) {
}
