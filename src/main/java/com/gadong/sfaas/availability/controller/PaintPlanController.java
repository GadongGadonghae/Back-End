package com.gadong.sfaas.availability.controller;

import com.gadong.sfaas.availability.service.PaintPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 도장 운영 판단 (11/21): 가동 라인 수에 맞춰 도장 몇 대를, 어느 경로로 쓸지 */
@Tag(name = "도장 운영")
@RestController
@RequestMapping("/api/paint")
public class PaintPlanController {
    private final PaintPlanService service;

    public PaintPlanController(PaintPlanService service) {
        this.service = service;
    }

    @Operation(summary = "P-01 도장 운영 판단", description = "지금 가동 라인·도장 상태로 도장 대수·경로를 계산한 제안 (바꾸지 않음)")
    @GetMapping("/plan")
    public PaintPlanService.PlanView plan() {
        return service.plan();
    }

    /** 경로·도장 운휴는 설비 운영 판단이라 공장장·생산관리팀장 (생산 목표는 바꾸지 않음) */
    @Operation(summary = "P-02 도장 운영 제안 적용", description = "적용하는 순간 다시 계산해서 경로 전환 · 도장 운휴(계획정지) · 다시 가동")
    @PreAuthorize("hasAnyRole('PLANT_HEAD', 'PROD_LEAD')")
    @PostMapping("/plan/apply")
    public ApplyResult apply() {
        List<String> done = service.apply("화면에서 적용");
        return new ApplyResult(done, service.plan());
    }

    public record ApplyResult(List<String> done, PaintPlanService.PlanView plan) {
    }
}
