package com.gadong.sfaas.report.flow;

import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.report.flow.ReportDtos.ReportCreateRequest;
import com.gadong.sfaas.report.flow.ReportDtos.ReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 보고서 결재·전송 (R-10 ~ R-14) + 임시 MES 수신함 (M-01). 명세: Front-end docs/api-spec-report-approval-mes.md */
@Tag(name = "보고서 결재·전송 · 임시 MES")
@RestController
@RequestMapping("/api")
public class ReportFlowController {

    private static final String DATE = "yyyy-MM-dd";

    private final ReportFlowService service;

    public ReportFlowController(ReportFlowService service) {
        this.service = service;
    }

    @Operation(summary = "R-10 보고서 작성 (생산관리팀장)",
            description = "kind = DAILY(targetKey 날짜, content = 일일 보고서 내용) · REPLAN(targetKey 비가동 eventId 또는 TARGET). "
                    + "REPLAN은 계획수정 보고서(R-03)를 만들어 담아요. 같은 kind·targetKey가 작성·결재 상태면 다시 작성(결재 처음부터), 전송됐으면 409")
    @PreAuthorize("hasRole('PROD_LEAD')")
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse create(@RequestBody ReportCreateRequest request) {
        return service.create(request);
    }

    @Operation(summary = "R-11 보고서 목록", description = "content 없이. 캠퍼스장은 전송(SENT)된 것만. replanId = 계획수정 보고서 번호로 찾기")
    @GetMapping("/reports")
    public PageResponse<ReportResponse> list(@RequestParam(required = false) String kind,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long replanId,
                                             @RequestParam(required = false) @DateTimeFormat(pattern = DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(pattern = DATE) LocalDate to,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return service.list(kind, status, replanId, from, to, page, size);
    }

    @Operation(summary = "R-12 보고서 상세", description = "content 포함. 캠퍼스장은 전송(SENT)된 것만")
    @GetMapping("/reports/{reportId}")
    public ReportResponse get(@PathVariable long reportId) {
        return service.get(reportId);
    }

    @Operation(summary = "R-13 결재 (공장장)", description = "작성(WRITTEN)만, 아니면 409. REPLAN이면 계획수정 보고서를 승인하고 현장에 적용 (R-04와 같음)")
    @PreAuthorize("hasRole('PLANT_HEAD')")
    @PostMapping("/reports/{reportId}/approve")
    public ReportResponse approve(@PathVariable long reportId) {
        return service.approve(reportId);
    }

    @Operation(summary = "R-14 보고서 전송 (공장장)", description = "결재 완료(APPROVED)만, 아니면 409. 전송 뒤에는 다시 작성·전송 안 됨")
    @PreAuthorize("hasRole('PLANT_HEAD')")
    @PostMapping("/reports/{reportId}/send")
    public ReportResponse send(@PathVariable long reportId) {
        return service.send(reportId);
    }

    @Operation(summary = "M-01 임시 MES 수신함 (캠퍼스장)", description = "전송(SENT)된 보고서만 최근 전송 순. campusId(BODY·ELEC)·factoryId로 거르기")
    @PreAuthorize("hasRole('CAMPUS')")
    @GetMapping("/mes/reports")
    public PageResponse<ReportResponse> mesInbox(@RequestParam(required = false) String campusId,
                                                 @RequestParam(required = false) Integer factoryId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return service.mesInbox(campusId, factoryId, page, size);
    }
}
