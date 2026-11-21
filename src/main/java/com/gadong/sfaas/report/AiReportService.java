package com.gadong.sfaas.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import java.util.stream.Collectors;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

@Service
public class AiReportService {
    private static final Logger log = LoggerFactory.getLogger(AiReportService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 라인 코드 A~F (명세서 lineId). 예: "A라인", "b 라인", "C line", "A, B라인", "A·C 라인" */
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9-])([A-F](?:\\s*[,·/와과및]\\s*[A-F])*)\\s*(?:라인|line)");
    private static final Pattern LINE_LETTER = Pattern.compile("(?i)[A-F]");
    private static final String BASE_PROMPT = """
            당신은 제조 현장의 생산계획 조정 보고서를 작성하는 전문가다.
            제공된 JSON 사실만 사용하고 값을 추측하지 않는다.
            수량 계산은 서버가 제공한 allocation을 그대로 사용한다.
            안전과 품질을 생산량보다 우선하며, 설비 자동 제어 명령은 내리지 않는다.
            한국어로 간결하게 작성하고 반드시 다음 JSON 형식만 반환한다.
            {"title":"...","summary":"...","actions":["..."],"risks":["..."],"markdown":"..."}
            """;
    /** 배경 지식 (11/21): resources/ai/system-knowledge.md를 서버가 켜질 때 한 번 읽어 지시문 뒤에 붙여요. 없으면 기본 지시문만 */
    static final String KNOWLEDGE = loadKnowledge();
    private static final String SYSTEM_PROMPT = KNOWLEDGE.isEmpty() ? BASE_PROMPT
            : BASE_PROMPT + "\n아래는 우리 공장과 시스템의 배경 지식이다. 사용자 JSON과 다르면 JSON을 따른다.\n\n" + KNOWLEDGE;

    private static String loadKnowledge() {
        try (var in = AiReportService.class.getResourceAsStream("/ai/system-knowledge.md")) {
            if (in == null) {
                LoggerFactory.getLogger(AiReportService.class).warn("AI 배경 지식 파일(/ai/system-knowledge.md)이 없어 기본 지시문만 씁니다");
                return "";
            }
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            LoggerFactory.getLogger(AiReportService.class).info("AI 배경 지식 {}자 불러옴 (/ai/system-knowledge.md)", text.length());
            return text;
        } catch (java.io.IOException e) {
            LoggerFactory.getLogger(AiReportService.class).warn("AI 배경 지식을 읽지 못했습니다: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 요청 유형 (배경 지식 6장과 맞물려요). 서버가 넣는 설비 칸 값으로 정해요:
     * "TARGET" = 목표 변경, "ORDER" = 추가 주문, PT* = 도장 고장, VI = 비전 고장, 그 밖 = 라인 설비 고장
     */
    static String requestType(AiReportRequest request) {
        String eq = request.equipmentId() == null ? "" : request.equipmentId().trim().toUpperCase();
        if (eq.equals("TARGET")) return "TARGET_CHANGE";
        if (eq.equals("ORDER")) return "ORDER";
        if (eq.startsWith("PT")) return "PAINT_FAULT";
        if (eq.equals("VI")) return "VISION_FAULT";
        return "LINE_FAULT";
    }

    private final AiReportProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    /** Claude API 클라이언트 (provider=anthropic 이고 키가 있을 때만, 처음 부를 때 만들어요) */
    private volatile AnthropicClient anthropicClient;

    public AiReportService(AiReportProperties properties, ObjectMapper objectMapper, RestClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds() > 0 ? properties.timeoutSeconds() : 30);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    public AiReportResponse generate(AiReportRequest request) {
        int baseShortage = Math.max(0, Math.max(request.targetQty(), request.dueQty()) - request.completedQty());
        AiReportResponse.RequestInterpretation interpretation = interpretAdditionalRequest(request.additionalRequest());
        List<String> requestedStoppedLines = interpretation.stoppedLines();
        int stoppedPlanQty = request.lineConditions().stream()
                .filter(line -> !line.lineId().equalsIgnoreCase(request.lineId()))
                .filter(line -> requestedStoppedLines.contains(line.lineId().toUpperCase()))
                .mapToInt(AiReportRequest.LineCondition::currentPlan)
                .sum();
        int shortage = baseShortage + stoppedPlanQty;
        if (stoppedPlanQty > 0) {
            List<String> appliedRules = new ArrayList<>(interpretation.appliedRules());
            appliedRules.add("추가 정지 라인의 남은 계획 " + stoppedPlanQty + "개를 재배정 수량에 포함");
            interpretation = new AiReportResponse.RequestInterpretation(
                    interpretation.excludedLines(), interpretation.stoppedLines(), interpretation.priority(), appliedRules);
        }
        List<AiReportResponse.Allocation> allocations = allocate(
                shortage, request.lineId(), request.lineConditions(), new LinkedHashSet<>(interpretation.excludedLines()));
        Draft draft;
        String fallbackReason = null;
        if (properties.mock()) {
            draft = mockDraft(request, shortage, allocations);
        } else {
            try {
                draft = callLlm(request, shortage, allocations);
            } catch (LlmUnavailableException e) {
                // 수량 계산은 이미 끝났으므로 문장만 템플릿으로 대신 만든다 (보고서 생성 자체는 실패시키지 않음)
                log.warn("LLM 보고서 생성 실패, 템플릿으로 대체합니다: {}", e.getMessage(), e.getCause());
                fallbackReason = e.getMessage();
                draft = mockDraft(request, shortage, allocations);
            }
        }
        boolean usedTemplate = properties.mock() || fallbackReason != null;

        return new AiReportResponse(
                UUID.randomUUID().toString(), request.downtimeId(), OffsetDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS),
                usedTemplate ? "mock-template" : properties.model(), usedTemplate, fallbackReason,
                shortage, draft.title(), draft.summary(), draft.actions(), allocations, draft.risks(), draft.markdown(), interpretation);
    }

    private AiReportResponse.RequestInterpretation interpretAdditionalRequest(String request) {
        if (request == null || request.isBlank()) {
            return new AiReportResponse.RequestInterpretation(
                    List.of(), List.of(), "BALANCED", List.of("기본 잔여 생산능력 기준 적용"));
        }

        Set<String> excludedLines = new LinkedHashSet<>();
        Set<String> stoppedLines = new LinkedHashSet<>();
        String normalized = request.replaceAll("\\s+", " ").trim();
        // 라인 언급마다 "그 언급부터 다음 라인 언급 전까지"만 보고 지시어를 찾는다.
        // 예: "A라인은 제외하고 F라인 정지" → A: 제외 / F: 정지 (앞 라인의 지시어가 뒤 라인에 섞이지 않게)
        List<MatchResult> mentions = LINE_PATTERN.matcher(normalized).results().toList();
        for (int i = 0; i < mentions.size(); i++) {
            MatchResult mention = mentions.get(i);
            int end = i + 1 < mentions.size() ? mentions.get(i + 1).start() : normalized.length();
            String context = normalized.substring(mention.end(), end);
            boolean exclude = context.matches(".*(제외|빼|배정하지|사용하지|금지).*");
            boolean stop = context.matches(".*(정지|중단|멈춰|멈춤|세워).*");
            for (String lineId : LINE_LETTER.matcher(mention.group(1)).results().map(r -> r.group().toUpperCase()).toList()) {
                if (exclude || stop) excludedLines.add(lineId);
                if (stop) stoppedLines.add(lineId);
            }
        }

        String priority = normalized.matches(".*(안전|품질).*우선.*") ? "SAFETY"
                : normalized.matches(".*(납기|긴급|마감).*우선.*") ? "DUE_DATE" : "BALANCED";
        List<String> rules = new ArrayList<>();
        stoppedLines.forEach(line -> rules.add(line + " 요청 정지 및 재배정 제외"));
        excludedLines.stream().filter(line -> !stoppedLines.contains(line))
                .forEach(line -> rules.add(line + " 재배정 제외"));
        if ("SAFETY".equals(priority)) rules.add("안전·품질 우선 검토");
        else if ("DUE_DATE".equals(priority)) rules.add("납기 준수 우선 검토");
        else rules.add("잔여 생산능력 기준 적용");
        return new AiReportResponse.RequestInterpretation(
                List.copyOf(excludedLines), List.copyOf(stoppedLines), priority, rules);
    }

    private List<AiReportResponse.Allocation> allocate(
            int shortage, String stoppedLine, List<AiReportRequest.LineCondition> conditions, Set<String> excludedLines) {
        List<AiReportRequest.LineCondition> candidates = conditions.stream()
                .filter(line -> !line.lineId().equals(stoppedLine))
                .filter(line -> !excludedLines.contains(line.lineId().toUpperCase()))
                .filter(line -> isAllocatable(line.status()))
                .filter(line -> line.availableCapacity() > 0)
                .sorted(Comparator.comparing(AiReportRequest.LineCondition::lineId))
                .toList();

        int totalCapacity = candidates.stream().mapToInt(AiReportRequest.LineCondition::availableCapacity).sum();
        int assignableQty = Math.min(shortage, totalCapacity);
        if (assignableQty == 0 || totalCapacity == 0) return List.of();

        Map<String, Integer> quantities = new LinkedHashMap<>();
        List<AllocationShare> shares = new ArrayList<>();
        int floorTotal = 0;
        for (AiReportRequest.LineCondition line : candidates) {
            double exact = (double) assignableQty * line.availableCapacity() / totalCapacity;
            int floor = Math.min((int) Math.floor(exact), line.availableCapacity());
            quantities.put(line.lineId(), floor);
            shares.add(new AllocationShare(line, exact - floor));
            floorTotal += floor;
        }

        shares.sort(Comparator.comparingDouble(AllocationShare::remainder).reversed()
                .thenComparing(share -> share.line().lineId()));
        int remainder = assignableQty - floorTotal;
        while (remainder > 0) {
            boolean allocated = false;
            for (AllocationShare share : shares) {
                AiReportRequest.LineCondition line = share.line();
                int current = quantities.get(line.lineId());
                if (current >= line.availableCapacity()) continue;
                quantities.put(line.lineId(), current + 1);
                remainder--;
                allocated = true;
                if (remainder == 0) break;
            }
            if (!allocated) break;
        }

        return candidates.stream()
                .map(line -> new AiReportResponse.Allocation(
                        line.lineId(), quantities.get(line.lineId()),
                        "잔여 능력 비율 " + String.format("%.1f", line.availableCapacity() * 100.0 / totalCapacity)
                                + "%, 가용 " + line.availableCapacity() + "개, 기존 계획 " + line.currentPlan() + "개"
                                + (line.constraint() == null || line.constraint().isBlank() ? "" : ", 제약: " + line.constraint())))
                .toList();
    }

    /** 명세서 상태값(R/S/I/D/DC) 중 재배정 후보가 될 수 있는 상태인지 (설정 ai.report.allocatable-statuses) */
    private boolean isAllocatable(String status) {
        return status != null && properties.allocatableStatuses().stream().anyMatch(status.trim()::equalsIgnoreCase);
    }

    private record AllocationShare(AiReportRequest.LineCondition line, double remainder) {
    }

    private Draft mockDraft(AiReportRequest request, int shortage, List<AiReportResponse.Allocation> allocations) {
        int assigned = allocations.stream().mapToInt(AiReportResponse.Allocation::quantity).sum();
        int unresolved = Math.max(0, shortage - assigned);
        String summary = request.equipmentName() + " 정지로 목표 대비 " + shortage
                + "개의 생산 부족이 예상됩니다. 정상 라인에 " + assigned + "개를 재배정하는 안을 제안합니다.";
        List<String> actions = new ArrayList<>();
        actions.add(request.equipmentName() + " 안전 정지 상태와 " + request.faultReason() + " 원인을 확인합니다.");
        allocations.forEach(a -> actions.add(a.lineId() + "라인에 " + a.quantity() + "개를 추가 배정합니다."));
        actions.add("계획 변경 전 생산관리자와 품질담당자의 승인을 받습니다.");

        List<String> risks = new ArrayList<>();
        risks.add("재배정 라인의 금형·자재·작업자 준비 상태를 확인해야 합니다.");
        if (unresolved > 0) risks.add("남은 " + unresolved + "개는 현재 가용 능력으로 해소할 수 없어 납기 협의가 필요합니다.");
        else risks.add("계산상 납기 수량 달성이 가능하지만 실제 복구시간에 따라 다시 계산해야 합니다.");

        String markdown = "# " + request.lineId() + "라인 생산계획 수정 제안\n\n"
                + "## 현황\n- 정지 설비: " + request.equipmentName() + "\n- 원인: " + request.faultReason()
                + "\n- 완료/목표: " + request.completedQty() + "/" + request.targetQty() + "개\n- 예상 부족: " + shortage + "개\n\n"
                + "## 제안\n" + actions.stream().map(v -> "- " + v).reduce("", (a, b) -> a + b + "\n")
                + "\n> 본 보고서는 의사결정 지원용 제안이며 설비나 생산계획을 자동 변경하지 않습니다.";
        return new Draft(request.lineId() + "라인 생산계획 수정 제안", summary, actions, risks, markdown);
    }

    private Draft callLlm(AiReportRequest request, int shortage, List<AiReportResponse.Allocation> allocations) {
        String json;
        try {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("requestType", requestType(request));
            facts.put("incident", request);
            facts.put("calculatedShortage", shortage);
            facts.put("allocation", allocations);
            json = chatJson(SYSTEM_PROMPT, objectMapper.writeValueAsString(facts));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmUnavailableException("보고서 사실 데이터를 만들지 못했습니다", e);
        }
        try {
            Draft draft = objectMapper.readValue(json, Draft.class);
            if (isBlank(draft.title()) || isBlank(draft.summary()) || draft.actions() == null || draft.risks() == null) {
                throw new LlmUnavailableException("LLM 응답 형식이 올바르지 않습니다", null);
            }
            return draft;
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmUnavailableException("LLM 응답 형식이 올바르지 않습니다", e);
        }
    }

    /** 설정상 시연 모드(LLM 안 부름)인지. 원인 추적(CauseTraceService)도 같은 설정을 따라요 */
    boolean mockMode() {
        return properties.mock();
    }

    String modelName() {
        return properties.model();
    }

    /**
     * LLM에 시스템 지시와 사용자 내용을 보내고 JSON 본문(문자열)을 받아요. ```json 으로 감싸 오면 벗겨 내요 (Gemini 등).
     * 키 없음·호출 실패·시간 초과는 LlmUnavailableException (호출한 쪽이 템플릿으로 대신해요)
     */
    String chatJson(String systemPrompt, String userContent) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new LlmUnavailableException("AI_REPORT_API_KEY가 설정되지 않았습니다", null);
        }
        if (properties.anthropic()) return stripFence(claudeJson(systemPrompt, userContent));
        try {
            Map<String, Object> body = Map.of(
                    "model", properties.model(),
                    "reasoning_effort", geminiEffort(properties.effort()),   // 11/21 Gemini 생각 깊이 (기본 low: 사실 정리라 빠르게, 안 주면 깊게 생각해서 1분 넘게 걸림)
                    "temperature", 0.2,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userContent)));

            String raw = null;
            // 503(사용량 폭주)·429(요청 한도)는 잠깐 뒤에 풀리는 경우가 많아 1초·2초 쉬고 두 번까지 다시 불러요
            for (int attempt = 0; ; attempt++) {
                try {
                    raw = restClient.post()
                            .uri("/chat/completions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(String.class);
                    break;
                } catch (HttpServerErrorException.ServiceUnavailable | HttpClientErrorException.TooManyRequests e) {
                    if (attempt >= 2) throw new LlmUnavailableException("LLM 서버가 바빠 응답하지 않았습니다 (" + e.getStatusCode().value() + "), 잠시 뒤 다시 만들어 주세요", e);
                    log.info("LLM {} 응답, {}초 뒤 다시 시도합니다", e.getStatusCode().value(), attempt + 1);
                    Thread.sleep(1000L * (attempt + 1));
                }
            }
            JsonNode content = objectMapper.readTree(raw).path("choices").path(0).path("message").path("content");
            return stripFence(content.asText());
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM 호출이 중단되었습니다", e);
        } catch (Exception e) {
            // 키·응답 본문이 섞일 수 있어 원인 메시지는 로그에만 남기고 화면에는 일반 문구만 보낸다
            throw new LlmUnavailableException("LLM 호출에 실패했거나 " + properties.timeoutSeconds() + "초 안에 응답하지 않았습니다", e);
        }
    }

    /**
     * Claude API (Anthropic 공식 Java SDK)로 JSON 문장을 받아요.
     * Claude Opus 5.5는 생각(thinking)이 늘 켜져 있고 temperature를 받지 않아서, 깊이는 effort로만 정해요 (기본 low: 사실 정리라 빠르게).
     * 거절(refusal)이나 빈 응답은 실패로 보고 호출한 쪽이 템플릿으로 대신해요.
     */
    private String claudeJson(String systemPrompt, String userContent) {
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(properties.model())
                    .maxTokens(16000L)
                    .system(systemPrompt)
                    .outputConfig(OutputConfig.builder().effort(effort()).build())
                    .addUserMessage(userContent)
                    .build();
            Message response = claude().messages().create(params);
            if (response.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
                throw new LlmUnavailableException("Claude가 이 요청에 답하지 않았습니다 (refusal)", null);
            }
            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining());
            if (text.isBlank()) throw new LlmUnavailableException("Claude 응답이 비어 있습니다", null);
            return text;
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // 키·응답 본문이 섞일 수 있어 원인 메시지는 로그에만 남기고 화면에는 일반 문구만 보낸다
            throw new LlmUnavailableException("Claude API 호출에 실패했거나 " + properties.timeoutSeconds() + "초 안에 응답하지 않았습니다", e);
        }
    }

    private AnthropicClient claude() {
        AnthropicClient c = anthropicClient;
        if (c == null) {
            synchronized (this) {
                if (anthropicClient == null) {
                    anthropicClient = AnthropicOkHttpClient.builder()
                            .apiKey(properties.apiKey())
                            .timeout(Duration.ofSeconds(properties.timeoutSeconds() > 0 ? properties.timeoutSeconds() : 30))
                            .maxRetries(1)
                            .build();
                }
                c = anthropicClient;
            }
        }
        return c;
    }

    private OutputConfig.Effort effort() {
        return switch (properties.effort() == null ? "" : properties.effort().trim().toLowerCase()) {
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "high" -> OutputConfig.Effort.HIGH;
            default -> OutputConfig.Effort.LOW;
        };
    }

    /** ```json ... ``` 으로 감싸 오면 벗겨 내요 */
    private static String stripFence(String text) {
        return text.trim().replaceAll("(?s)^```(?:json)?\\s*(.*?)\\s*```$", "$1");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Draft(String title, String summary, List<String> actions, List<String> risks, String markdown) {
    }

    /** LLM 을 쓸 수 없을 때(키 없음·호출 실패·시간 초과·응답 형식 오류). 템플릿으로 대체하는 신호로만 쓴다. */
    static class LlmUnavailableException extends RuntimeException {
        LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Gemini(OpenAI 호환) reasoning_effort: low · medium · high 만 받아요. 그 밖의 값은 low (11/21) */
    static String geminiEffort(String effort) {
        String e = effort == null ? "" : effort.trim().toLowerCase();
        return e.equals("medium") || e.equals("high") ? e : "low";
    }
}
