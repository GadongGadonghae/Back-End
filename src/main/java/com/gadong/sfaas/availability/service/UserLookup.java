package com.gadong.sfaas.availability.service;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * users 테이블 조회. users는 auth 패키지(BE2) 소유라 엔티티가 없어서 읽기만 해요.
 * TODO(BE2 연동): auth의 User 엔티티·Repository가 생기면 이 클래스를 지우고 그쪽을 써요.
 */
@Component
class UserLookup {

    private final NamedParameterJdbcTemplate jdbc;

    UserLookup(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean exists(int userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id = :id", Map.of("id", userId), Long.class) > 0;
    }

    /** 사용자가 없거나 userId가 null이면 null */
    String name(Integer userId) {
        if (userId == null) return null;
        List<String> names = jdbc.queryForList("SELECT user_name FROM users WHERE user_id = :id", Map.of("id", userId), String.class);
        return names.isEmpty() ? null : names.get(0);
    }
}
