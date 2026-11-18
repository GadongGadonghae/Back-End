package com.gadong.sfaas.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** C-01 /api/auth/login: BCrypt 비밀번호 확인 → JWT 발급, 토큰이 있어야 다른 API를 쓸 수 있어요. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        jdbc.update("insert into users(user_id, login_id, password_hash, user_name, role) values (1, 'admin', ?, '김관리', 'ADMIN')",
                encoder.encode("pw1234"));
    }

    private String login(String loginId, String password) {
        return "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void loginThenCallApiWithToken() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("admin", "pw1234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("김관리"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");

        mvc.perform(get("/api/lines").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/lines")).andExpect(status().isUnauthorized()); // 토큰 없음
        mvc.perform(get("/api/lines").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordOrUnknownUserIs401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("admin", "nope")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("ghost", "pw1234")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("admin", "pw1234")))
                .andExpect(status().isUnauthorized()); // 예전 주소는 없음 (인증 전이라 401)
    }
}
