
package com.gadong.sfaas.auth.service;

import com.gadong.sfaas.auth.dto.UserCreateRequest;
import com.gadong.sfaas.auth.dto.UserResponse;
import com.gadong.sfaas.auth.entity.User;
import com.gadong.sfaas.auth.repository.UserRepository;
import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // C-02: 사용자 목록 조회
    @Transactional(readOnly = true)
    public List<UserResponse> getUsers(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(
                ErrorCode.INVALID_REQUEST,
                "잘못된 페이지 요청입니다."
            );
        }

        return userRepository.findAll(PageRequest.of(page, size))
            .getContent()
            .stream()
            .map(UserResponse::from)
            .toList();
    }

    // C-03: 사용자 등록
    @Transactional
    public String createUser(UserCreateRequest request) {

        // 1. 로그인 ID 중복 검사
        if (userRepository.findByLoginId(request.loginId()).isPresent()) {
            throw new BusinessException(
                ErrorCode.USER_ALREADY_EXISTS,
                ErrorCode.USER_ALREADY_EXISTS.defaultMessage()
            );
        }

        // 2. 비밀번호 BCrypt 암호화
        String passwordHash = passwordEncoder.encode(request.password());

        // 3. 사용자 객체 생성
        User user = new User(
            request.loginId(),
            passwordHash,
            request.userName(),
            request.role(),
            request.email(),
            request.memo()
        );

        // 4. DB 저장
        userRepository.save(user);

        // 5. 등록한 로그인 ID 반환
        return user.getLoginId();
    }
}
