package com.gadong.sfaas.auth.dto;

import com.gadong.sfaas.auth.entity.User;
import java.time.LocalDateTime;

public record UserResponse(
    String loginId,
    String userName,
    String role,
    String email,
    String memo,
    LocalDateTime lastLoginAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getLoginId(),
            user.getUserName(),
            user.getRole(),
            user.getEmail(),
            user.getMemo(),
            user.getLastLoginAt()
        );
    }
}
