package com.gadong.sfaas.notify;

import com.gadong.sfaas.availability.dto.PlanningDtos.ReassignmentDetailResponse;
import com.gadong.sfaas.availability.dto.PlanningDtos.ReassignmentDetailResponse.Target;
import com.gadong.sfaas.global.util.Kst;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 슬랙 메시지(Block Kit) 만들기. 웹훅 1건 = 메시지 1개.
 * 구성: 제목 → 고장 장비 정보 → AI 요약 → 보고서(수량·재배정 표·조치·위험) → 보고서 보기 버튼 → 보낸 사람.
 * 슬랙 제한: header 150자, section text 3000자, field 2000자, 블록 50개 → 넘으면 잘라요.
 */
final class SlackMessageBuilder {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int HEADER_MAX = 150;
    private static final int TEXT_MAX = 2900;
    private static final int FIELD_MAX = 1900;
    private static final int LIST_MAX = 5;

    private SlackMessageBuilder() {}

    static Map<String, Object> build(ReassignmentDetailResponse r, SlackNotifyService.Incident incident,
                                     ReportSummarizer.Summary summary, String sender, String appBaseUrl, LocalDateTime now) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        String headline = "🚨 " + incident.equipmentName() + " 고장 · 계획수정 보고서";
        blocks.add(Map.of("type", "header", "text", plain(cut(headline, HEADER_MAX))));

        // ── 고장 장비 ──
        String state = incident.endedAt() == null
                ? "🔴 정지 중 (" + duration(Duration.between(Kst.local(r.startedAt()), now)) + "째)"
                : "🟢 해제 " + time(Kst.of(incident.endedAt())) + " (" + duration(Duration.between(Kst.local(r.startedAt()), incident.endedAt())) + ")";
        blocks.add(fields(
                "*설비*\n" + esc(incident.equipmentName()) + " (`" + esc(r.equipmentId()) + "`)",
                "*라인 · 공정*\n" + esc(nz(incident.lineName(), "공용")) + " · " + esc(incident.processName()),
                "*비가동 사유*\n" + esc(incident.codeName()) + (blank(r.detail()) ? "" : " — " + esc(r.detail())),
                "*발생 · 상태*\n" + time(r.startedAt()) + "\n" + state));
        blocks.add(Map.of("type", "divider"));

        // ── AI 요약 ──
        String summaryTitle = ReportSummarizer.TEMPLATE_MODEL.equals(summary.model()) ? "*📝 요약* _(템플릿 문장)_" : "*🤖 AI 요약*";
        blocks.add(section(summaryTitle + "\n" + bullets(summary.lines(), LIST_MAX)));
        blocks.add(Map.of("type", "divider"));

        // ── 보고서 ──
        String approval = r.approved() ? "✅ 승인" + (blank(r.approvedBy()) ? "" : " · " + esc(r.approvedBy())) : "⏳ 검토 대기";
        blocks.add(section("*📄 계획수정 보고서* `" + esc(r.documentNo()) + "`\n"
                + time(r.createdAt()) + " 작성" + (blank(r.createdBy()) ? "" : " · " + esc(r.createdBy())) + " · " + approval
                + (r.ai() == null || blank(r.ai().title()) ? "" : "\n_" + esc(r.ai().title()) + "_")));
        blocks.add(fields(
                "*부족 수량*\n" + num(r.lossQty()) + "개",
                "*재배정 후 목표 미달*\n" + (r.unmetQty() == null ? "-" : r.unmetQty() == 0 ? "없음 (목표 달성 가능)" : num(r.unmetQty()) + "개"),
                "*계산 기준*\n" + kindLabel(r.kind()) + (r.baseAt() == null ? "" : " · " + time(r.baseAt()) + " 기준"),
                "*멈춘 쪽 계획 · 실적*\n" + (r.stoppedPlan() == null ? "-" : num(r.stoppedPlan())) + " / "
                        + (r.stoppedDone() == null ? "-" : num(r.stoppedDone()))));
        blocks.add(section("*재배정 계획*\n" + allocationTable(r.details())));
        if (r.ai() != null && r.ai().actions() != null && !r.ai().actions().isEmpty()) {
            blocks.add(section("*조치*\n" + bullets(r.ai().actions(), LIST_MAX)));
        }
        if (r.ai() != null && r.ai().risks() != null && !r.ai().risks().isEmpty()) {
            blocks.add(section("*위험*\n" + bullets(r.ai().risks(), LIST_MAX)));
        }

        // ── 보고서 보기 버튼 (주소를 설정한 경우만) ──
        if (!blank(appBaseUrl) && (appBaseUrl.startsWith("http://") || appBaseUrl.startsWith("https://"))) {
            String url = appBaseUrl.replaceAll("/+$", "") + "/reports/replan/" + r.reportId();
            blocks.add(Map.of("type", "actions", "elements", List.of(Map.of(
                    "type", "button", "text", plain("보고서 보기"), "url", url, "style", "primary"))));
        }
        blocks.add(Map.of("type", "context", "elements", List.of(Map.of("type", "mrkdwn", "text",
                "SFaaS 라인 가동 현황 모니터링" + (blank(sender) ? "" : " · 보낸 사람 " + esc(sender))
                        + " · 요약 " + esc(summary.model())))));

        Map<String, Object> payload = new LinkedHashMap<>();
        // text = 알림 미리보기·블록을 못 그리는 환경용 대체 문장
        payload.put("text", cut(esc(headline + " — " + String.join(" ", summary.lines())), TEXT_MAX));
        payload.put("blocks", blocks);
        return payload;
    }

    // ── 표시 도우미 ──

    /** 라인 ID 한 글자(A~F)면 "A라인", 도장 보고서의 설비 ID(PT1·PT2)는 그대로 */
    static String targetLabel(String id) {
        return id != null && id.length() == 1 ? id + "라인" : id;
    }

    static String time(OffsetDateTime t) {
        return t == null ? "-" : t.atZoneSameInstant(Kst.ZONE).format(TIME);
    }

    private static String allocationTable(List<Target> details) {
        if (details == null || details.isEmpty()) return "_맡길 수 있는 라인이 없습니다._";
        StringBuilder sb = new StringBuilder("```\n");
        // %n 은 Windows에서 \r\n 이 돼서 줄바꿈은 \n 으로 고정해요
        sb.append(String.format("%-6s %10s %8s %8s\n", "대상", "남은능력", "비율", "추가"));
        int total = 0;
        for (Target d : details) {
            sb.append(String.format("%-6s %10s %7.1f%% %8s\n",
                    targetLabel(d.targetLineId()), num(d.spareCapacity()), d.share(), "+" + num(d.addQty())));
            total += d.addQty();
        }
        sb.append(String.format("%-6s %10s %8s %8s\n", "합계", "", "", "+" + num(total)));
        sb.append("```");
        return cut(sb.toString(), TEXT_MAX);
    }

    private static String kindLabel(String kind) {
        if (kind == null) return "-";
        return switch (kind) {
            case "line" -> "라인 전체 정지";
            case "paint" -> "도장 1대 → 다른 1대";
            case "vision" -> "대체 설비 없음";
            default -> kind;
        };
    }

    private static String duration(Duration d) {
        long min = Math.max(0, d.toMinutes());
        return min >= 60 ? (min / 60) + "시간 " + (min % 60) + "분" : min + "분";
    }

    private static String bullets(List<String> items, int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < items.size() && i < max; i++) out.add("• " + esc(items.get(i)));
        if (items.size() > max) out.add("_외 " + (items.size() - max) + "건_");
        return cut(String.join("\n", out), TEXT_MAX);
    }

    private static Map<String, Object> section(String mrkdwn) {
        return Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", cut(mrkdwn, TEXT_MAX)));
    }

    private static Map<String, Object> fields(String... texts) {
        List<Map<String, Object>> f = new ArrayList<>();
        for (String t : texts) f.add(Map.of("type", "mrkdwn", "text", cut(t, FIELD_MAX)));
        return Map.of("type", "section", "fields", f);
    }

    private static Map<String, Object> plain(String text) {
        return Map.of("type", "plain_text", "text", text, "emoji", true);
    }

    /** 슬랙 mrkdwn 예약 문자(&, <, >)를 바꿔요. 안 바꾸면 <!channel> 같은 문장이 그대로 동작할 수 있어요 */
    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String num(int n) {
        return String.format("%,d", n);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s, String fallback) {
        return blank(s) ? fallback : s;
    }
}
