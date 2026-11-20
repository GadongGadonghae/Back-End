package com.gadong.sfaas.report.flow;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gadong.sfaas.auth.Roles;
import com.gadong.sfaas.auth.entity.User;
import com.gadong.sfaas.auth.repository.UserRepository;
import com.gadong.sfaas.auth.service.CurrentUser;
import com.gadong.sfaas.availability.dto.PlanningDtos.ReassignmentDetailResponse;
import com.gadong.sfaas.availability.service.ReplanService;
import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.global.util.Checks;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.report.flow.ReportDtos.Person;
import com.gadong.sfaas.report.flow.ReportDtos.ReportCreateRequest;
import com.gadong.sfaas.report.flow.ReportDtos.ReportResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보고서 결재·전송 + 임시 MES 수신함 (v0.3.15, MES 명세 docs/api-spec-report-approval-mes.md).
 * <ul>
 *   <li>R-10 작성(생산관리팀장): REPLAN은 기존 계획수정 보고서(R-03, ReplanService)를 만들어 그 내용을 담아요. DAILY는 화면이 계산한 내용을 저장</li>
 *   <li>🆕 11/20 상태: 작성 중 DRAFT(AI 보고서를 만들기만 함) → 작성 WRITTEN(작성 완료·결재 요청, R-15) ⇄ 회수(R-16, 결재 전) → 결재 APPROVED → 전송 SENT</li>
 *   <li>R-13 결재(공장장): WRITTEN만. REPLAN이면 계획수정 보고서를 승인하고 현장에 적용 (R-04와 같음)</li>
 *   <li>R-14 전송(공장장): APPROVED만 → SENT. 전송 뒤에는 다시 작성·전송 안 됨 (409)</li>
 *   <li>M-01 MES 수신함(캠퍼스장만): SENT만</li>
 * </ul>
 * 예전 화면(R-03·R-04)으로 만들고 승인해도 여기 기록돼요 (syncReplan·syncApproved) → 그대로 전송할 수 있어요.
 */
@Service
@Transactional(readOnly = true)
public class ReportFlowService {

    public static final String DAILY = "DAILY";
    public static final String REPLAN = "REPLAN";

    private final ReportRepository reports;
    private final ReplanService replanService;
    private final CurrentUser currentUser;
    private final UserRepository users;
    private final ObjectMapper objectMapper;
    private final String campusId;
    private final int factoryId;

    public ReportFlowService(ReportRepository reports, ReplanService replanService, CurrentUser currentUser, UserRepository users,
                             ObjectMapper objectMapper,
                             @Value("${plant.campus-id:BODY}") String campusId, @Value("${plant.factory-id:1}") int factoryId) {
        this.reports = reports;
        this.replanService = replanService;
        this.currentUser = currentUser;
        this.users = users;
        this.objectMapper = objectMapper;
        this.campusId = campusId;
        this.factoryId = factoryId;
    }

    // ── R-10 작성 ──

    @Transactional
    public ReportResponse create(ReportCreateRequest request) {
        if (request == null || request.kind() == null) throw invalid("kind(DAILY·REPLAN)가 필요합니다");
        String kind = request.kind().trim().toUpperCase();
        String key = request.targetKey() == null ? "" : request.targetKey().trim();
        LocalDateTime now = Kst.now();
        if (DAILY.equals(kind)) {
            LocalDate day = key.isEmpty() ? now.toLocalDate() : parseDay(key);
            if (request.content() == null || request.content().isNull()) throw invalid("일일 보고서 내용(content)이 필요합니다");
            Report r = writable(DAILY, day.toString());
            r.write("일일 생산 보고서 · " + day, request.content().toString(), null, currentUser.id(), now);
            return toResponse(reports.save(r), true);
        }
        if (!REPLAN.equals(kind)) throw invalid("kind는 DAILY 또는 REPLAN입니다");
        if (key.isEmpty()) throw invalid("REPLAN의 targetKey(비가동 eventId 또는 TARGET)가 필요합니다");
        boolean target = "TARGET".equalsIgnoreCase(key);
        // 11/20: 계획수정 보고서는 만들 때마다 새 결재 기록 (같은 고장·같은 날 목표라도 다시 계획을 고칠 수 있게)
        ReassignmentDetailResponse replan = target
                ? replanService.create(null, request.additionalRequest(), "target")
                : replanService.create(parseLong(key), request.additionalRequest(), null);
        return toResponse(syncReplan(replan), true);
    }

    /** 계획수정 보고서가 만들어지면 (R-03·R-05·R-10) 결재 흐름에 작성 중(DRAFT)으로 기록. 공장장에게는 결재 요청(R-15) 때 알려요 */
    @Transactional
    public Report syncReplan(ReassignmentDetailResponse replan) {
        // 보고서마다 따로 (11/20): 같은 고장·같은 날 목표로 다시 만들어도 앞 결재(전송 완료 포함)와 섞이지 않고 새로 결재
        String key = replan.downtimeId() != null ? replan.downtimeId() + "#" + replan.reportId()
                : "target".equals(replan.kind()) ? targetKeyOf(replan.createdAt().toLocalDate()) + "#" + replan.reportId() : "ORDER-" + replan.reportId();
        Report r = writable(REPLAN, key);
        r.draft(titleOf(replan), json(replan), replan.reportId(), currentUser.id(), Kst.now());
        return reports.save(r);
    }

    // ── R-15 결재 요청 · R-16 회수 (생산관리팀장, 11/20) ──

    /** 작성 완료 · 결재 요청: 작성 중(DRAFT)만. content = 그 순간 화면 문서 (임시 MES 열람용, 없으면 그대로) */
    @Transactional
    public ReportResponse request(long reportId, JsonNode content) {
        Report r = find(reportId);
        if (!Report.DRAFT.equals(r.getStatus())) throw conflict(r, "작성 중인 보고서만 결재 요청할 수 있습니다");
        String snapshot = content == null || content.isNull() ? null
                : REPLAN.equals(r.getKind()) ? json(java.util.Map.of("doc", content)) : content.toString();
        r.request(currentUser.id(), Kst.now(), snapshot);
        return toResponse(r, true);
    }

    /** 회수: 결재 요청(WRITTEN)을 거둬 작성 중으로. 결재된 뒤에는 못 해요 */
    @Transactional
    public ReportResponse recall(long reportId) {
        Report r = find(reportId);
        if (!Report.WRITTEN.equals(r.getStatus())) throw conflict(r, "결재 요청 중인 보고서만 회수할 수 있습니다");
        r.recall();
        return toResponse(r, true);
    }

    /** 계획수정 보고서 배정 값 고치기 (생산관리팀장): 결재 요청 전(작성 중)에만 */
    @Transactional
    public ReassignmentDetailResponse editReplan(long replanId, java.util.Map<String, Integer> addQty) {
        reports.findFirstByReplanReportIdOrderByReportIdDesc(replanId).ifPresent(r -> {
            if (!Report.DRAFT.equals(r.getStatus())) throw conflict(r, "결재 요청 전(작성 중)에만 값을 고칠 수 있습니다. 회수한 뒤 고쳐 주세요");
        });
        ReassignmentDetailResponse replan = replanService.editAllocations(replanId, addQty);
        reports.findFirstByReplanReportIdOrderByReportIdDesc(replanId).ifPresent(r -> r.draft(r.getTitle(), json(replan), replanId, r.getWrittenBy(), r.getWrittenAt()));
        return replan;
    }

    /** 계획수정 보고서가 승인되면 (예전 화면 R-04·R-06) 결재 흐름도 결재 완료로 (작성 중·결재 요청 상태일 때만) */
    @Transactional
    public void syncApproved(ReassignmentDetailResponse replan) {
        reports.findFirstByReplanReportIdOrderByReportIdDesc(replan.reportId())
                .filter(r -> Report.DRAFT.equals(r.getStatus()) || Report.WRITTEN.equals(r.getStatus()))
                .ifPresent(r -> r.approve(currentUser.id(), Kst.now(), json(replan)));
    }

    // ── R-13 결재 · R-14 전송 ──

    @Transactional
    public ReportResponse approve(long reportId) {
        Report r = find(reportId);
        if (!Report.WRITTEN.equals(r.getStatus())) throw conflict(r, "작성 상태인 보고서만 결재할 수 있습니다");
        String content = null;
        if (r.getReplanReportId() != null) {
            // 추가 주문 배정(order)은 승인·적용(R-06: 계획정지 끝내기 + 목표 + 라인 가동 명령), 나머지는 승인하면 계획·도장 경로에 적용 (R-04)
            if (r.getTargetKey().startsWith("ORDER-")) replanService.apply(r.getReplanReportId());
            else replanService.approve(r.getReplanReportId());
            // 결재 요청 때 찍어 둔 문서({"doc": …})가 있으면 그대로 두고(임시 MES 열람), 없으면 승인 뒤 보고서로
            JsonNode now = node(r.getContent());
            if (now == null || !now.has("doc")) content = json(replanService.report(r.getReplanReportId()));
        }
        r.approve(currentUser.id(), Kst.now(), content);
        return toResponse(r, true);
    }

    @Transactional
    public ReportResponse send(long reportId) {
        Report r = find(reportId);
        if (!Report.APPROVED.equals(r.getStatus())) throw conflict(r, "결재 완료 후에만 전송할 수 있습니다");
        r.send(currentUser.id(), Kst.now());
        return toResponse(r, true);
    }

    // ── R-11 목록 · R-12 상세 · M-01 MES 수신함 ──

    public PageResponse<ReportResponse> list(String kind, String status, Long replanId, LocalDate from, LocalDate to, int page, int size) {
        Checks.page(page, size);
        boolean campus = isCampus();
        Stream<Report> s = reports.findAll().stream()
                .filter(r -> kind == null || kind.equalsIgnoreCase(r.getKind()))
                .filter(r -> status == null || status.equalsIgnoreCase(r.getStatus()))
                .filter(r -> replanId == null || replanId.equals(r.getReplanReportId()))
                .filter(r -> !campus || Report.SENT.equals(r.getStatus()))
                .filter(r -> from == null || !r.getWrittenAt().toLocalDate().isBefore(from))
                .filter(r -> to == null || !r.getWrittenAt().toLocalDate().isAfter(to))
                .sorted(Comparator.comparing(Report::getWrittenAt).reversed());
        return page(s.toList(), page, size);
    }

    public ReportResponse get(long reportId) {
        Report r = find(reportId);
        if (isCampus() && !Report.SENT.equals(r.getStatus())) throw new BusinessException(ErrorCode.REPORT_NOT_FOUND, "보고서 " + reportId + "를 찾을 수 없습니다");
        return toResponse(r, true);
    }

    public PageResponse<ReportResponse> mesInbox(String campus, Integer factory, int page, int size) {
        Checks.page(page, size);
        List<Report> sent = reports.findAll().stream()
                .filter(r -> Report.SENT.equals(r.getStatus()))
                .filter(r -> campus == null || campus.equalsIgnoreCase(r.getCampusId()))
                .filter(r -> factory == null || factory.equals(r.getFactoryId()))
                .sorted(Comparator.comparing(Report::getSentAt).reversed())
                .toList();
        // 수신함은 몇 건뿐이라 내용까지 줘요 (다른 컴퓨터의 캠퍼스장도 문서를 열람)
        List<ReportResponse> items = sent.stream().skip((long) (page - 1) * size).limit(size).map(r -> toResponse(r, true)).toList();
        return new PageResponse<>(items, sent.size(), page, size);
    }

    // ── 도우미 ──

    /** 같은 kind·targetKey 보고서 (없으면 새로). 전송된 건 다시 작성 못 해요 */
    private Report writable(String kind, String key) {
        Optional<Report> found = reports.findByKindAndTargetKey(kind, key);
        if (found.isPresent() && Report.SENT.equals(found.get().getStatus())) throw conflict(found.get(), "전송한 보고서는 다시 작성할 수 없습니다");
        return found.orElseGet(() -> new Report(kind, key, campusId, factoryId));
    }

    private static String targetKeyOf(LocalDate day) {
        return "TARGET-" + day;
    }

    private static String titleOf(ReassignmentDetailResponse r) {
        if (r.ai() != null && r.ai().title() != null && !r.ai().title().isBlank()) return "계획수정 보고서 · " + r.ai().title();
        return "계획수정 보고서 · " + ("target".equals(r.kind()) ? "생산 목표 변경" : "order".equals(r.kind()) ? "추가 주문 배정" : r.equipmentId());
    }

    private Report find(long id) {
        return reports.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND, "보고서 " + id + "를 찾을 수 없습니다"));
    }

    private static BusinessException conflict(Report r, String why) {
        return new BusinessException(ErrorCode.REPORT_STATE_CONFLICT, why + " (현재 상태 " + r.getStatus() + ")");
    }

    private boolean isCampus() {
        return currentUser.get().map(u -> Roles.CAMPUS.equals(u.getRole())).orElse(false);
    }

    private PageResponse<ReportResponse> page(List<Report> all, int page, int size) {
        List<ReportResponse> items = all.stream().skip((long) (page - 1) * size).limit(size).map(r -> toResponse(r, false)).toList();
        return new PageResponse<>(items, all.size(), page, size);
    }

    private ReportResponse toResponse(Report r, boolean withContent) {
        return new ReportResponse(r.getReportId(), r.getKind(), r.getTargetKey(), r.getTitle(), r.getStatus(), r.getCampusId(), r.getFactoryId(),
                r.getReplanReportId(), person(r.getWrittenBy()), Kst.of(r.getWrittenAt()), person(r.getApprovedBy()), Kst.of(r.getApprovedAt()),
                person(r.getSentBy()), Kst.of(r.getSentAt()), withContent ? node(r.getContent()) : null);
    }

    private Person person(Integer userId) {
        return userId == null ? null : new Person(userId, users.findById(userId).map(User::getUserName).orElse(null));
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("보고서 내용을 저장하지 못했습니다", e);
        }
    }

    private JsonNode node(String s) {
        if (s == null) return null;
        try {
            return objectMapper.readTree(s);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static LocalDate parseDay(String s) {
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            throw invalid("DAILY의 targetKey는 날짜(yyyy-MM-dd)입니다");
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw invalid("REPLAN의 targetKey는 비가동 eventId(숫자) 또는 TARGET입니다");
        }
    }
}
