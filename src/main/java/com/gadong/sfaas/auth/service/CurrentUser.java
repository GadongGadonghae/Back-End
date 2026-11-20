package com.gadong.sfaas.auth.service;

import com.gadong.sfaas.auth.entity.User;
import com.gadong.sfaas.auth.repository.UserRepository;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 지금 요청한 로그인 사용자 (JWT의 loginId → users). 저장할 때 "누가 바꿨는지"를 남기려고 써요 (11/20) */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 로그인 사용자. 로그인 안 했거나 users에 없으면 비어 있어요 */
    public Optional<User> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return Optional.empty();
        return userRepository.findByLoginId(auth.getName());
    }

    /** 로그인 사용자 번호 (없으면 null) */
    public Integer id() {
        return get().map(User::getUserId).orElse(null);
    }

    /** 사용자 번호 → 이름 (없으면 null) */
    public String nameOf(Integer userId) {
        return userId == null ? null : userRepository.findById(userId).map(User::getUserName).orElse(null);
    }
}
