
package com.gadong.sfaas.auth.service;

import com.gadong.sfaas.auth.dto.LoginRequest;
import com.gadong.sfaas.auth.dto.LoginResponse;
import com.gadong.sfaas.auth.entity.User;
import com.gadong.sfaas.auth.jwt.JwtTokenProvider;
import com.gadong.sfaas.auth.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtTokenProvider jwtTokenProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {

        // 1. 로그인 ID로 사용자 조회
        User user = userRepository.findByLoginId(request.loginId())
            .orElseThrow(() ->
                new BadCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.")
            );

        // 2. 비밀번호 검증 (BCrypt)
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        // 3. 로그인 성공 시 마지막 로그인 시각 갱신
        user.updateLastLoginAt(LocalDateTime.now());

        // 4. JWT 토큰 발급
        String accessToken = jwtTokenProvider.createToken(
            user.getLoginId(),
            user.getRole()
        );

        // 5. 응답 반환
        return new LoginResponse(
            accessToken,
            user.getUserName(),
            user.getRole()
        );
    }
}
