
package com.gadong.sfaas.auth.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "login_id", nullable = false, unique = true, length = 30)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "user_name", nullable = false, length = 20)
    private String userName;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "memo", length = 200)
    private String memo;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /** 라인 담당자(LINE_WORKER)의 담당 라인. 쉼표로 (예: "A,B"). 다른 권한은 비움 (v0.3.15) */
    @Column(name = "lines", length = 30)
    private String lines;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;


    public User(
        String loginId,
        String passwordHash,
        String userName,
        String role,
        String email,
        String memo
    ) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.userName = userName;
        this.role = role;
        this.email = email;
        this.memo = memo;
    }


    /** 담당 라인 목록 (빈 칸·공백 정리, 대문자) */
    public java.util.List<String> lineList() {
        if (lines == null || lines.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(lines.split(",")).map(String::trim).map(String::toUpperCase).filter(x -> !x.isEmpty()).distinct().toList();
    }

    public void assignLines(java.util.List<String> lineIds) {
        this.lines = lineIds == null || lineIds.isEmpty() ? null : String.join(",", lineIds);
    }

    public void updateLastLoginAt(LocalDateTime time) {
        this.lastLoginAt = time;
    }
}
