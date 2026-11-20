package com.gadong.sfaas.report;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-reports")
public class AiReportController {
    private final AiReportService service;

    public AiReportController(AiReportService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('PRODUCTION_MANAGER')") // AI 리포트 작성 = 생산관리팀장만 (11/20)
    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public AiReportResponse generate(@Valid @RequestBody AiReportRequest request) {
        return service.generate(request);
    }
}
