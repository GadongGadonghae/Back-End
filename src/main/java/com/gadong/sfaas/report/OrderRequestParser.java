package com.gadong.sfaas.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 추가 주문 요청 해석 (계획수정 보고서 "추가 주문 배정").
 * 현장 말("지금 A~D 4개만 돌리는데 추가 주문 300개, 오늘 안에. 한 라인 더 돌려줘")을 계산 조건으로 바꿔요.
 * LLM이 있으면 LLM이 해석하고, 없거나 실패하면 규칙(정규식)으로 해석해요. 어느 쪽이든 결과는 코드가 다시 검사해요 (라인 A~F, 수량 범위).
 * 수량 계산은 하지 않아요 (계산은 ReplanService).
 */
@Component
public class OrderRequestParser {
    private static final Logger log = LoggerFactory.getLogger(OrderRequestParser.class);
    private static final List<String> LINES = List.of("A", "B", "C", "D", "E", "F");
    public static final int MAX_QTY = 100_000;

    private static final String SYSTEM_PROMPT = """
            당신은 제조 현장의 생산계획 담당자가 한 말을 계산 조건 JSON으로 바꾸는 해석기다.
            라인은 A, B, C, D, E, F 여섯 개다. 말에 없는 값은 추측하지 말고 null 또는 빈 배열로 둔다.
            - addQty: 추가 주문 수량 (정수)
            - dueAt: 오늘 안의 납기 시각 "HH:mm". "오늘 안", "오늘 중"은 "24:00". 말이 없으면 null
            - runningLines: 지금 가동 중이라고 라인 이름(A~F)을 직접 말한 라인들. "A~D 4개"는 ["A","B","C","D"].
              "라인 4개 돌아가고 있어"처럼 수만 말하고 이름이 없으면 빈 배열 (어느 라인인지 추측 금지)
            - newLines: 새로 가동하라고 말한 라인들 ("D라인 하나 가동시키고", "F라인 추가로 돌려" → ["D"], ["F"]).
              라인을 정하지 않고 "한 라인 더"라고만 하면 빈 배열
            - addLineCount: 새로 가동할 라인 수를 숫자로만 말했으면 그 수 ("한 라인 더" = 1). 없으면 null
            - excludedLines: 쓰지 말라고 한 라인들
            - setupMinutes: 라인을 새로 켜는 준비 시간(분)을 말했으면 그 값, 없으면 null
            반드시 다음 JSON만 반환한다.
            {"addQty":300,"dueAt":"24:00","runningLines":["A","B","C","D"],"newLines":[],"addLineCount":1,"excludedLines":[],"setupMinutes":null}
            """;

    private final AiReportService ai;
    private final ObjectMapper objectMapper;

    public OrderRequestParser(AiReportService ai, ObjectMapper objectMapper) {
        this.ai = ai;
        this.objectMapper = objectMapper;
    }

    /**
     * 해석 결과. parsedBy = "AI · 모델명" 또는 "규칙". notes = 해석하면서 고치거나 버린 것 (화면에 그대로 보여 줘요)
     */
    public record Parsed(Integer addQty, String dueAt, List<String> runningLines, List<String> newLines, Integer addLineCount,
                         List<String> excludedLines, Integer setupMinutes, String parsedBy, List<String> notes) {}

    public Parsed parse(String request) {
        String text = request == null ? "" : request.trim();
        if (text.isEmpty()) return new Parsed(null, null, List.of(), List.of(), null, List.of(), null, "규칙", List.of());
        if (!ai.mockMode()) {
            try {
                return checkAgainstText(fromJson(objectMapper.readTree(ai.chatJson(SYSTEM_PROMPT, text)), "AI · " + ai.modelName()), rules(text));
            } catch (Exception e) {
                log.warn("추가 주문 요청 AI 해석 실패, 규칙으로 해석합니다: {}", e.getMessage());
                Parsed r = rules(text);
                List<String> notes = new ArrayList<>(r.notes());
                notes.add(0, "AI 해석에 실패해 규칙으로 해석했습니다");
                return new Parsed(r.addQty(), r.dueAt(), r.runningLines(), r.newLines(), r.addLineCount(), r.excludedLines(),
                        r.setupMinutes(), r.parsedBy(), notes);
            }
        }
        return rules(text);
    }

    /**
     * AI 해석을 말과 대조해요. 라인은 말에 실제로 나온 라인만 인정하고 (AI가 "라인 4개"에서 A~D를 지어내는 일을 막아요),
     * AI가 놓친 새 라인은 규칙 해석으로 채워요. 고친 것은 notes에 남겨 화면에 보여 줘요
     */
    static Parsed checkAgainstText(Parsed ai, Parsed rule) {
        List<String> notes = new ArrayList<>(ai.notes());
        Set<String> said = new LinkedHashSet<>();
        said.addAll(rule.runningLines());
        said.addAll(rule.newLines());
        said.addAll(rule.excludedLines());
        List<String> newLines = keepSaid(ai.newLines(), said, "새로 가동", notes);
        if (newLines.isEmpty() && !rule.newLines().isEmpty()) {
            newLines = rule.newLines();
            notes.add("새로 가동할 " + String.join(",", newLines) + "라인은 말에서 직접 찾았습니다");
        }
        List<String> running = new ArrayList<>(keepSaid(ai.runningLines(), said, "가동 중", notes));
        running.removeAll(newLines);
        List<String> excluded = keepSaid(ai.excludedLines(), said, "제외", notes);
        Integer qty = ai.addQty() != null ? ai.addQty() : rule.addQty();
        return new Parsed(qty, ai.dueAt() != null ? ai.dueAt() : rule.dueAt(), List.copyOf(running), newLines,
                newLines.isEmpty() ? ai.addLineCount() : null, excluded, ai.setupMinutes() != null ? ai.setupMinutes() : rule.setupMinutes(),
                ai.parsedBy(), notes);
    }

    private static List<String> keepSaid(List<String> lines, Set<String> said, String what, List<String> notes) {
        List<String> kept = lines.stream().filter(said::contains).toList();
        List<String> dropped = lines.stream().filter(l -> !said.contains(l)).toList();
        if (!dropped.isEmpty()) notes.add("말에 없는 " + String.join(",", dropped) + "라인을 AI가 " + what + "으로 봐서 뺐습니다");
        return kept;
    }

    private Parsed fromJson(JsonNode n, String by) {
        List<String> notes = new ArrayList<>();
        Integer qty = n.path("addQty").isNumber() ? n.path("addQty").asInt() : null;
        if (qty != null && (qty <= 0 || qty > MAX_QTY)) { notes.add("추가 수량 " + qty + "은 범위 밖이라 버렸습니다"); qty = null; }
        String due = n.path("dueAt").isTextual() ? n.path("dueAt").asText().trim() : null;
        if (due != null && !due.matches("([01]\\d|2[0-3]):[0-5]\\d|24:00")) { notes.add("납기 \"" + due + "\"는 시각 형식이 아니라 버렸습니다"); due = null; }
        Integer count = n.path("addLineCount").isNumber() ? n.path("addLineCount").asInt() : null;
        if (count != null && (count < 0 || count > LINES.size())) count = null;
        Integer setup = n.path("setupMinutes").isNumber() ? n.path("setupMinutes").asInt() : null;
        if (setup != null && (setup < 0 || setup > 600)) setup = null;
        return new Parsed(qty, due, lines(n.path("runningLines"), notes), lines(n.path("newLines"), notes), count,
                lines(n.path("excludedLines"), notes), setup, by, notes);
    }

    private static List<String> lines(JsonNode arr, List<String> notes) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode x : arr) {
            String v = x.asText().trim().toUpperCase().replace("라인", "");
            if (LINES.contains(v)) out.add(v);
            else notes.add("라인 \"" + x.asText() + "\"은 없는 라인이라 버렸습니다");
        }
        return List.copyOf(out);
    }

    // ── 규칙 해석 (시연 모드·AI 실패) ──

    private static final Pattern QTY = Pattern.compile("(\\d[\\d,]*)\\s*(?:개|ea|EA)");
    private static final Pattern RANGE = Pattern.compile("(?i)([A-F])\\s*[~\\-]\\s*([A-F])");
    private static final Pattern LINE_LIST = Pattern.compile("(?i)(?<![A-Z0-9-])([A-F](?:\\s*[,·/와과및]\\s*[A-F])*)\\s*(?:라인|line)");
    private static final Pattern SETUP = Pattern.compile("(?:준비|셋업|세팅)[^0-9]{0,8}(\\d+)\\s*분|(\\d+)\\s*분\\s*(?:준비|셋업|세팅)");
    private static final Pattern DUE = Pattern.compile("(\\d{1,2})\\s*시(?:\\s*(\\d{1,2})\\s*분)?\\s*(?:까지|안에|전에|납기)");
    private static final Map<String, Integer> COUNT_WORDS = Map.of("한", 1, "하나", 1, "두", 2, "둘", 2, "세", 3, "셋", 3);

    public static Parsed rules(String text) {
        List<String> notes = new ArrayList<>();
        Integer qty = null;
        Matcher q = QTY.matcher(text);
        while (q.find()) {
            int v = Integer.parseInt(q.group(1).replace(",", ""));
            // "4개 라인"의 4는 라인 수라 빼요
            String after = text.substring(q.end()).trim();
            if (after.startsWith("라인")) continue;
            if (v > 0 && v <= MAX_QTY) { qty = v; break; }
        }
        String due = null;
        Matcher d = DUE.matcher(text);
        if (d.find()) {
            int h = Integer.parseInt(d.group(1)), m = d.group(2) == null ? 0 : Integer.parseInt(d.group(2));
            if (h <= 24 && m < 60) due = String.format("%02d:%02d", h, h == 24 ? 0 : m);
        } else if (text.matches(".*오늘\\s*(안|중|까지).*")) {
            due = "24:00";
        }
        Integer setup = null;
        Matcher s = SETUP.matcher(text);
        if (s.find()) setup = Integer.parseInt(s.group(1) != null ? s.group(1) : s.group(2));

        // 라인 언급마다 뒤따르는 말로 가동 중·새로 가동·제외를 가려요
        Set<String> running = new LinkedHashSet<>(), added = new LinkedHashSet<>(), excluded = new LinkedHashSet<>();
        String norm = RANGE.matcher(text).replaceAll(m -> expand(m.group(1), m.group(2)) + "라인");
        List<java.util.regex.MatchResult> mentions = LINE_LIST.matcher(norm).results().toList();
        for (int i = 0; i < mentions.size(); i++) {
            java.util.regex.MatchResult mm = mentions.get(i);
            String ctx = norm.substring(mm.end(), i + 1 < mentions.size() ? mentions.get(i + 1).start() : norm.length());
            ctx = ctx.length() > 25 ? ctx.substring(0, 25) : ctx;
            List<String> ids = Pattern.compile("(?i)[A-F]").matcher(mm.group(1)).results().map(r -> r.group().toUpperCase()).toList();
            if (ctx.matches(".*(제외|빼|쓰지|사용하지).*")) excluded.addAll(ids);
            else if (ctx.matches(".*(더|추가|새로|켜|돌려|가동).*") && !ctx.matches(".*(돌리고|돌아가|돌리는|가동 중|가동중).*")) added.addAll(ids);
            else running.addAll(ids);
        }
        Integer count = null;
        Matcher c = Pattern.compile("(한|하나|두|둘|세|셋|\\d)\\s*(?:개\\s*)?라인\\s*(?:을|를)?\\s*(?:더|추가)").matcher(text);
        if (c.find()) count = COUNT_WORDS.getOrDefault(c.group(1), c.group(1).matches("\\d") ? Integer.parseInt(c.group(1)) : null);
        if (qty == null) notes.add("추가 수량을 찾지 못했습니다 (예: 300개)");
        return new Parsed(qty, due, List.copyOf(running), List.copyOf(added), count, List.copyOf(excluded), setup, "규칙", notes);
    }

    /**
     * 요청에서 정지하라고 한 라인 (11/21): "D라인 정지", "E라인 멈춰", "F라인은 세워", "C라인 꺼". "정지 중"·"멈춰 있는"은 빼요
     */
    public static List<String> stopLines(String text) {
        if (text == null || text.isBlank()) return List.of();
        String norm = RANGE.matcher(text).replaceAll(m -> expand(m.group(1), m.group(2)) + "라인");
        List<java.util.regex.MatchResult> mentions = LINE_LIST.matcher(norm).results().toList();
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < mentions.size(); i++) {
            java.util.regex.MatchResult mm = mentions.get(i);
            String ctx = norm.substring(mm.end(), i + 1 < mentions.size() ? mentions.get(i + 1).start() : norm.length());
            ctx = ctx.length() > 15 ? ctx.substring(0, 15) : ctx;
            if (ctx.matches(".*(정지|멈춰|멈추|중단|세워|세우|꺼|끄).*") && !ctx.matches("^\\s*(은|는|이|가)?\\s*(이미\\s*)?(정지\\s*중|멈춰\\s*있|쉬고|정지된).*")) {
                Pattern.compile("(?i)[A-F]").matcher(mm.group(1)).results().forEach(r -> out.add(r.group().toUpperCase()));
            }
        }
        return List.copyOf(out);
    }

    private static String expand(String a, String b) {
        char x = Character.toUpperCase(a.charAt(0)), y = Character.toUpperCase(b.charAt(0));
        StringBuilder out = new StringBuilder();
        for (char ch = (char) Math.min(x, y); ch <= Math.max(x, y); ch++) out.append(out.length() == 0 ? "" : ",").append(ch);
        return out.toString();
    }
}
