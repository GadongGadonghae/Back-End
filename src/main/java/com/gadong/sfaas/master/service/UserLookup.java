package com.gadong.sfaas.master.service;

import com.gadong.sfaas.auth.entity.User;
import com.gadong.sfaas.auth.repository.UserRepository;
import org.springframework.stereotype.Component;

/** 조치 담당자·승인자 번호 확인과 이름 조회. users는 auth(BE2) 소유라 읽기만 해요 */
@Component
public class UserLookup {

    private final UserRepository users;

    public UserLookup(UserRepository users) {
        this.users = users;
    }

    public boolean exists(int userId) {
        return users.existsById(userId);
    }

    /** 사용자가 없거나 userId가 null이면 null */
    public String name(Integer userId) {
        return userId == null ? null : users.findById(userId).map(User::getUserName).orElse(null);
    }
}
