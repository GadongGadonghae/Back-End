
package com.gadong.sfaas.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gadong.sfaas.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;

    private String createAdminAndGetToken() throws Exception {
        jdbc.update(
            "INSERT INTO users(login_id, password_hash, user_name, role) VALUES (?, ?, ?, ?)",
            "testadmin",
            encoder.encode("admin1234"),
            "테스트관리자",
            "PLANT_MANAGER"
        );

        String response = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "loginId": "testadmin",
                      "password": "admin1234"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("accessToken").asText();
    }

    @Test
    void getUsersReturnsListWithoutPassword() throws Exception {
        String token = createAdminAndGetToken();

        mvc.perform(get("/api/users")
                .param("page", "0")
                .param("size", "20")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].loginId").value("testadmin"))
            .andExpect(jsonPath("$[0].userName").value("테스트관리자"))
            .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void createUserEncryptsPasswordAndReturns201() throws Exception {
        String token = createAdminAndGetToken();

        mvc.perform(post("/api/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "loginId": "worker01",
                      "password": "worker1234",
                      "userName": "작업자1",
                      "role": "PRODUCTION_MANAGER",
                      "email": "worker@sfaas.local",
                      "memo": "테스트 계정"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.loginId").value("worker01"));

        var user = userRepository.findByLoginId("worker01").orElseThrow();

        assertNotEquals("worker1234", user.getPasswordHash());
        assertTrue(encoder.matches("worker1234", user.getPasswordHash()));
        assertEquals("작업자1", user.getUserName());
    }

    @Test
    void usersApiRequiresJwt() throws Exception {
        mvc.perform(get("/api/users"))
            .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }
    @Test
    void duplicateLoginIdReturns409() throws Exception {
        String token = createAdminAndGetToken();

        mvc.perform(post("/api/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "loginId": "testadmin",
                      "password": "another1234",
                      "userName": "중복사용자",
                      "role": "PRODUCTION_MANAGER"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username = "pm", roles = "PRODUCTION_MANAGER")
    void onlyPlantManagerManagesUsers() throws Exception {
        // 11/20 계정 권한: 사용자 관리는 공장장만 → 생산관리팀장은 403
        mvc.perform(get("/api/users")).andExpect(status().isForbidden());
    }

    @Test
    void createUserRejectsUnknownRole() throws Exception {
        String token = createAdminAndGetToken();
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"loginId": "x1", "password": "pw123456", "userName": "엑스", "role": "ADMIN"}
                    """))
            .andExpect(status().isBadRequest());
    }
}
