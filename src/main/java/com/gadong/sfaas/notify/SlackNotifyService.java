package com.gadong.sfaas.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gadong.sfaas.auth.entity.User;
import com.gadong.sfaas.auth.repository.UserRepository;
import com.gadong.sfaas.availability.dto.PlanningDtos.ReassignmentDetailResponse;
import com.gadong.sfaas.availability.service.ReplanService;
import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import com.gadong.sfaas.global.util.Kst;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * N-01 계획수정 보고서 → 슬랙.
 * 1. 보고서(R-02와 같은 내용)와 고장 장비 정보를 모으고  2. LLM으로 3줄 요약  3. 웹훅으로 1건 보내요.
 * 보낸 기록은 DB에 남기지 않아요 (팀 결정). 실패하면 예외로 화면에 이유를 돌려줘요.
 */
@Service
public class SlackNotifyService {
    private static final Logger log = LoggerFactory.getLogger(SlackNotifyService.class);

    /** 보고서 응답에 없는 장비 표시 정보 (설비 이름, 라인·공정 이름, 비가동 코드 이름, 해제 시각) */
    public record Incident(String equipmentName, String lineName, String processName, String codeName, LocalDateTime endedAt) {}

    private final SlackProperties properties;
    private final ReplanService replanService;
    private final ReportSummarizer summarizer;
    private final SlackWebhookClient webhook;
    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public SlackNotifyService(SlackProperties properties, ReplanService replanService, ReportSummarizer summarizer,
                              SlackWebhookClient webhook, NamedParameterJdbcTemplate jdbc, UserRepository userRepository,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.replanService = replanService;
        this.summarizer = summarizer;
        this.webhook = webhook;
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    public SlackSendResponse send(long reportId) {
        // LLM 요약(20초 안팎)을 기다린 뒤에 실패하지 않도록 웹훅 설정부터 확인해요
        if (!properties.mock() && !properties.configured()) {
            throw new BusinessException(ErrorCode.SLACK_NOT_CONFIGURED);
        }
        ReassignmentDetailResponse report = replanService.report(reportId);
        // 추가 주문·목표 변경 보고서(11/21)는 고장 건이 없어요 → incident = null (메시지·요약이 보고서 내용으로)
        Incident incident = report.downtimeId() == null ? null : incident(report.downtimeId());
        ReportSummarizer.Summary summary = summarizer.summarize(report, incident);
        Map<String, Object> payload = SlackMessageBuilder.build(report, incident, summary, currentUserName(),
                AppAddress.resolve(properties.appBaseUrl()), Kst.now());

        if (properties.mock()) {
            log.info("[slack.mock] 보고서 {} 슬랙 메시지 (보내지 않음):\n{}", reportId, toJson(payload));
        } else {
            try {
                webhook.post(payload);
            } catch (RestClientResponseException e) {
                // 슬랙은 실패 이유를 본문에 짧게 줘요 (invalid_blocks, no_service, channel_not_found 등). 웹훅 주소는 남기지 않아요
                log.warn("슬랙 전송 실패 (보고서 {}): HTTP {} {}", reportId, e.getStatusCode().value(), e.getResponseBodyAsString());
                throw new BusinessException(ErrorCode.SLACK_SEND_FAILED,
                        "슬랙 전송에 실패했습니다 (" + e.getStatusCode().value() + " " + e.getResponseBodyAsString() + ")");
            } catch (RestClientException e) {
                log.warn("슬랙 전송 실패 (보고서 {}): {}", reportId, e.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.SLACK_SEND_FAILED, "슬랙에 연결하지 못했습니다 (인터넷 연결을 확인해 주세요)");
            }
            log.info("슬랙 전송 완료: 보고서 {}", reportId);
        }
        return new SlackSendResponse(reportId, properties.mock(), Kst.of(Kst.now()), summary.lines(), summary.model(),
                summary.fallbackReason());
    }

    private Incident incident(long downtimeId) {
        List<Incident> found = jdbc.query("SELECT e.equipment_name, l.line_name, p.process_name, COALESCE(c.code_name, d.code), d.ended_at"
                        + " FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                        + " JOIN process p ON p.process_id = e.process_id"
                        + " LEFT JOIN line l ON l.line_id = e.line_id"
                        + " LEFT JOIN downtime_code c ON c.code = d.code WHERE d.downtime_id = :id",
                Map.of("id", downtimeId),
                (rs, n) -> new Incident(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Kst.local(rs.getObject(5))));
        if (found.isEmpty()) throw new BusinessException(ErrorCode.DOWNTIME_NOT_FOUND, "비가동 이력 " + downtimeId + "를 찾을 수 없습니다");
        return found.get(0);
    }

    /** 보낸 사람 = 로그인 사용자 이름. 없으면 로그인 ID */
    private String currentUserName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        return userRepository.findByLoginId(auth.getName()).map(User::getUserName).orElse(auth.getName());
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
