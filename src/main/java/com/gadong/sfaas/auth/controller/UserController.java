
package com.gadong.sfaas.auth.controller;

import com.gadong.sfaas.auth.dto.UserCreateRequest;
import com.gadong.sfaas.auth.dto.UserResponse;
import com.gadong.sfaas.auth.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // C-02: 사용자 목록 조회 (사용자 관리 = 공장장만, 11/20)
    @PreAuthorize("hasRole('PLANT_HEAD')")
    @GetMapping
    public ResponseEntity<List<UserResponse>> getUsers(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(userService.getUsers(page, size));
    }

    // C-03: 사용자 등록 (공장장만)
    @PreAuthorize("hasRole('PLANT_HEAD')")
    @PostMapping
    public ResponseEntity<Map<String, String>> createUser(
        @Valid @RequestBody UserCreateRequest request
    ) {
        String loginId = userService.createUser(request);

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(Map.of("loginId", loginId));
    }
}
