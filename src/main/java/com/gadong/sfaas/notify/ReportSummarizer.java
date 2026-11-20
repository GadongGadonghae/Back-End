package com.gadong.sfaas.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gadong.sfaas.availability.dto.PlanningDtos.ReassignmentDetailResponse;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.report.AiReportProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 계획수정 보고서를 슬랙용 3줄로 요약해요.
 * LLM 설정은 AI 보고서와 같은 ai.report.* 를 그대로 써요 (키·모델·주소를 따로 두지 않음).
 * ai.report.mock=true 이거나 키가 없거나 호출이 실패하면 템플릿 문장으로 대신해요 (전송 자체는 실패시키지 않음).
 */
@Component
public class ReportSummarizer {
    private static final Logger log = LoggerFactory.getLogger(ReportSummarizer.class);
    static final String TEMPLATE_MODEL = "template";
    private static final int MAX_LINES = 4;
    private static final int MAX_LINE_LENGTH = 200;
    private static final String SYSTEM_PROMPT = """
            당신은 제조 현장 관리자에게 슬랙으로 보낼 계획수정 보고서(설비 고장 또는 추가 주문·목표 변경) 알림을 요약한다.
            제공된 JSON 사실만 사용하고, 숫자를 바꾸거나 없는 내용을 추측하지 않는다.
            한국어로 3줄 이내, 각 줄은 80자 이내로 쓴다.
            1줄: 어떤 설비가 언제 왜 멈췄는지 (equipment가 없으면 추가 주문·목표 변경 요청 내용). 2줄: 재배정 계획의 핵심(라인별 추가 수량). 3줄: 목표 영향 또는 가장 중요한 조치.
            반드시 다음 JSON 형식만 반환한다. {"lines":["...","...","..."]}
            """;

    /** 요약 결과. fallbackReason 이 있으면 LLM 대신 템플릿 문장이에요 */
    public record Summary(List<String> lines, String model, String fallbackReason) {}

    private final AiReportProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ReportSummarizer(AiReportProperties properties, ObjectMapper objectMapper, RestClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds() > 0 ? properties.timeoutSeconds() : 30);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    public Summary summarize(ReassignmentDetailResponse report, SlackNotifyService.Incident incident) {
        if (properties.mock()) {
            return new Summary(template(report, incident), TEMPLATE_MODEL, null);
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            return new Summary(template(report, incident), TEMPLATE_MODEL, "AI_REPORT_API_KEY가 설정되지 않았습니다");
        }
        try {
            return new Summary(callLlm(report, incident), properties.model(), null);
        } catch (Exception e) {
            // 키·응답 본문이 섞일 수 있어 원인은 로그에만 남기고 화면에는 일반 문구만 보내요
            log.warn("슬랙 요약 LLM 호출 실패, 템플릿으로 대체합니다: {}", e.toString());
            return new Summary(template(report, incident), TEMPLATE_MODEL,
                    "LLM 요약에 실패했거나 " + properties.timeoutSeconds() + "초 안에 응답하지 않았습니다");
        }
    }

    private List<String> callLlm(ReassignmentDetailResponse report, SlackNotifyService.Incident incident) throws Exception {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (incident != null) {
            facts.put("equipment", incident.equipmentName() + " (" + report.equipmentId() + ")");
            facts.put("line", incident.lineName());
            facts.put("process", incident.processName());
            facts.put("downtimeCode", incident.codeName());
            facts.put("startedAt", report.startedAt());
            facts.put("endedAt", Kst.of(incident.endedAt()));
            facts.put("ongoing", incident.endedAt() == null);
        }
        facts.put("detail", report.detail());   // 추가 주문·목표 변경은 요청 문장 (11/21)
        facts.put("reportKind", report.kind());
        facts.put("shortageQty", report.lossQty());
        facts.put("factoryTargetQty", report.targetQty());
        facts.put("unmetQtyAfterReassign", report.unmetQty());
        facts.put("allocations", report.details().stream()
                .map(d -> Map.of("target", SlackMessageBuilder.targetLabel(d.targetLineId()), "addQty", d.addQty()))
                .toList());
        if (report.ai() != null) {
            facts.put("reportTitle", report.ai().title());
            facts.put("reportSummary", report.ai().summary());
            facts.put("reportActions", report.ai().actions());
            facts.put("reportRisks", report.ai().risks());
        }
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "reasoning_effort", geminiEffort(properties.effort()),   // 11/21 Gemini 생각 깊이 (기본 low: 사실 정리라 빠르게, 안 주면 깊게 생각해서 1분 넘게 걸림)
                "temperature", 0.2,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", objectMapper.writeValueAsString(facts))));

        String raw = restClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        String content = objectMapper.readTree(raw).path("choices").path(0).path("message").path("content").asText().trim();
        // 모델에 따라 JSON을 ```json ... ``` 으로 감싸 보내는 경우가 있어 벗겨 내요 (Gemini 등)
        String json = content.replaceAll("(?s)^```(?:json)?\\s*(.*?)\\s*```$", "$1");
        JsonNode lines = objectMapper.readTree(json).path("lines");
        List<String> out = new ArrayList<>();
        for (JsonNode n : lines) {
            String s = n.asText("").trim();
            if (!s.isEmpty()) out.add(s.length() > MAX_LINE_LENGTH ? s.substring(0, MAX_LINE_LENGTH) + "…" : s);
            if (out.size() == MAX_LINES) break;
        }
        if (out.isEmpty()) throw new IllegalStateException("LLM 요약 응답 형식이 올바르지 않습니다");
        return out;
    }

    /** LLM 없이 숫자만으로 만드는 요약 */
    static List<String> template(ReassignmentDetailResponse report, SlackNotifyService.Incident incident) {
        List<String> lines = new ArrayList<>();
        if (incident == null) {
            // 추가 주문·목표 변경 (고장 건 없음, 11/21)
            lines.add(("order".equals(report.kind()) ? "추가 주문" : "생산 목표 변경") + " 계획수정 보고서입니다"
                    + (report.detail() == null || report.detail().isBlank() ? "." : " (요청: " + report.detail() + ")."));
        } else {
            String cause = incident.codeName() + (report.detail() == null || report.detail().isBlank() ? "" : "(" + report.detail() + ")");
            String state = incident.endedAt() == null ? "현재 정지 중" : SlackMessageBuilder.time(Kst.of(incident.endedAt())) + " 해제";
            lines.add(incident.equipmentName() + " " + cause + " · " + SlackMessageBuilder.time(report.startedAt()) + " 발생, " + state + ".");
        }

        List<String> adds = report.details().stream().filter(d -> d.addQty() > 0)
                .map(d -> SlackMessageBuilder.targetLabel(d.targetLineId()) + " +" + String.format("%,d", d.addQty()) + "개")
                .toList();
        lines.add(adds.isEmpty()
                ? "부족 " + String.format("%,d", report.lossQty()) + "개를 맡길 수 있는 라인이 없습니다."
                : "부족 " + String.format("%,d", report.lossQty()) + "개를 " + String.join(", ", adds) + "로 나눠 맡기는 안입니다.");

        if (report.unmetQty() != null) {
            lines.add(report.unmetQty() > 0
                    ? "재배정해도 오늘 목표 대비 " + String.format("%,d", report.unmetQty()) + "개 미달이 예상됩니다."
                    : "재배정하면 오늘 목표를 맞출 수 있습니다.");
        }
        return lines;
    }

    /** Gemini(OpenAI 호환) reasoning_effort: low · medium · high 만 받아요. 그 밖의 값은 low (11/21) */
    static String geminiEffort(String effort) {
        String e = effort == null ? "" : effort.trim().toLowerCase();
        return e.equals("medium") || e.equals("high") ? e : "low";
    }
}
