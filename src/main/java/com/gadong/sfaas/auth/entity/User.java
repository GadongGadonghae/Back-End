
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


    public void updateLastLoginAt(LocalDateTime time) {
        this.lastLoginAt = time;
    }
}
