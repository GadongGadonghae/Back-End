package com.gadong.sfaas.availability.service;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.PaintRouteRequest;
import com.gadong.sfaas.monitoring.service.MonitoringService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도장 운영 (P-01 조회 · P-02 적용, 11/21). 계산은 {@link PaintPlanner}, 여기는 DB에서 사실을 모으고 적용만 해요.
 * <ul>
 *   <li>가동 라인 = 계획정지(S)가 아니고 고장(D) 설비가 없는 라인 (고장 라인은 제품이 안 나와 도장 부하가 없어요)</li>
 *   <li>쓸 수 있는 도장 = 지금 상태가 고장(D)·통신 끊김(DC)이 아닌 도장. 계획정지(S)로 쉬는 도장은 다시 켤 수 있어 포함</li>
 *   <li>도장을 끄기 = 그 도장에 계획정지(PL_STANDBY, 물량 적음 운휴) 시작 → 서버 판정 S, 가동률 계산에서 빠져요 (손실 아님)</li>
 *   <li>도장을 켜기 = 그 도장의 PL_STANDBY 계획정지를 끝내요</li>
 *   <li>경로 바꾸기 = M-09 (기록 + 현장에 MQTT {ctl}/route)</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class PaintPlanService {
    private static final Logger log = LoggerFactory.getLogger(PaintPlanService.class);
    /** 도장 운휴 계획정지 코드 */
    public static final String STANDBY = "PL_STANDBY";

    private final NamedParameterJdbcTemplate jdbc;
    private final MonitoringService monitoringService;

    @Value("${plant.shift-end:24:00}")
    private String shiftEnd = "24:00";

    public PaintPlanService(NamedParameterJdbcTemplate jdbc, MonitoringService monitoringService) {
        this.jdbc = jdbc;
        this.monitoringService = monitoringService;
    }

    /** 지금 사실 */
    public record Facts(List<String> running, Map<String, String> current, Map<String, String> defaults,
                        Map<String, String> boothState, List<String> usable, List<String> onNow, Set<String> parked,
                        double paintCt, double lineCt) {
        double load() {
            return lineCt <= 0 ? 0 : paintCt / lineCt;
        }
    }

    /** 화면·보고서용 결과 */
    public record PlanView(OffsetDateTime at, double paintCt, double lineCt, double lineLoad, double rho, double rhoMerge,
                           List<String> running, List<Booth> booths, List<PaintPlanner.Change> changes,
                           List<String> turnOn, List<String> turnOff, boolean overloaded, boolean noBooth,
                           boolean changeNeeded, String verdict, List<String> reasons, Integer savedMinutes) {
    }

    /** 도장 1대: 지금 상태 · 지금 받는 가동 라인과 부하 · 제안 */
    public record Booth(String paintId, String state, boolean parked, boolean usable,
                        List<String> nowLines, double nowLoad, List<String> planLines, double planLoad, boolean planOn) {
    }

    public Facts facts() {
        return facts(null);
    }

    /** running을 주면 그 라인이 생산한다고 보고 계산해요 (보고서: 결재 뒤 상태 미리 보기) */
    public Facts facts(List<String> runningOverride) {
        Map<String, String> current = new LinkedHashMap<>(), defaults = new LinkedHashMap<>();
        jdbc.query("SELECT line_id, current_paint_id, default_paint_id FROM line ORDER BY line_id", Map.of(), rs -> {
            current.put(rs.getString(1), rs.getString(2));
            defaults.put(rs.getString(1), rs.getString(3));
        });
        List<String> running = runningOverride != null ? runningOverride.stream().filter(current::containsKey).sorted().toList()
                : jdbc.queryForList("SELECT l.line_id FROM line l WHERE NOT EXISTS (SELECT 1 FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                + " WHERE e.line_id = l.line_id AND d.ended_at IS NULL AND d.status IN ('S', 'D')) ORDER BY l.line_id", Map.of(), String.class);
        Map<String, String> state = new LinkedHashMap<>();
        for (String pt : booths()) state.put(pt, "R");
        jdbc.query("SELECT s.equipment_id, s.status FROM equipment_status_history s JOIN equipment e ON e.equipment_id = s.equipment_id"
                + " WHERE s.ended_at IS NULL AND e.process_id = 'PAINTING'", Map.of(), rs -> { state.put(rs.getString(1), rs.getString(2)); });
        Set<String> parked = new LinkedHashSet<>(jdbc.queryForList("SELECT DISTINCT d.equipment_id FROM downtime_event d JOIN equipment e ON e.equipment_id = d.equipment_id"
                + " WHERE e.process_id = 'PAINTING' AND d.status = 'S' AND d.ended_at IS NULL", Map.of(), String.class));
        List<String> usable = state.entrySet().stream().filter(e -> !"D".equals(e.getValue()) && !"DC".equals(e.getValue())).map(Map.Entry::getKey).toList();
        List<String> onNow = usable.stream().filter(p -> !parked.contains(p)).toList();
        return new Facts(running, current, defaults, state, usable, onNow, parked, ct("'PAINTING'"), ct("'SLITTING', 'PRESS', 'WELDING'"));
    }

    public PlanView plan() {
        return view(facts());
    }

    public PlanView plan(List<String> runningAfter) {
        return view(facts(runningAfter));
    }

    PlanView view(Facts f) {
        PaintPlanner.Plan p = PaintPlanner.plan(f.running(), f.current(), f.defaults(), f.usable(), f.onNow(), f.load());
        double u = f.load();
        List<Booth> booths = new ArrayList<>();
        for (String pt : f.boothState().keySet()) {
            List<String> nowLines = f.running().stream().filter(l -> pt.equals(f.current().get(l))).toList();
            List<String> planLines = f.running().stream().filter(l -> pt.equals(p.routes().get(l))).toList();
            booths.add(new Booth(pt, f.boothState().get(pt), f.parked().contains(pt), f.usable().contains(pt),
                    nowLines, r2(nowLines.size() * u), planLines, r2(planLines.size() * u), p.on().contains(pt)));
        }
        List<String> turnOn = p.on().stream().filter(x -> f.parked().contains(x)).toList();
        List<String> turnOff = p.off().stream().filter(x -> !f.parked().contains(x)).toList();
        boolean changeNeeded = !p.noBooth() && (!p.changes().isEmpty() || !turnOn.isEmpty() || !turnOff.isEmpty());
        Integer saved = null;
        if (!turnOff.isEmpty()) {
            long min = Math.max(0, Duration.between(Kst.now(), shiftEndAt()).toMinutes());
            saved = (int) (min * turnOff.size());
        }
        return new PlanView(Kst.of(Kst.now()), f.paintCt(), f.lineCt(), r2(u), PaintPlanner.RHO, PaintPlanner.RHO_MERGE,
                f.running(), booths, p.changes(), turnOn, turnOff, p.overloaded(), p.noBooth(), changeNeeded,
                verdict(f, p, turnOn, turnOff), reasons(f, p, u), saved);
    }

    /** 한 줄 판단 */
    private String verdict(Facts f, PaintPlanner.Plan p, List<String> turnOn, List<String> turnOff) {
        if (p.noBooth()) return "쓸 수 있는 도장이 없습니다. 도장 복구가 먼저입니다";
        if (f.running().isEmpty()) return turnOff.isEmpty() ? "가동 라인이 없어 도장도 쉬고 있습니다" : "가동 라인이 없어 도장 " + String.join("·", turnOff) + "을 쉬게 합니다";
        StringBuilder s = new StringBuilder("가동 " + f.running().size() + "개 라인 · 도장 부하 합 " + pct(p.totalLoad()) + " → 도장 "
                + p.on().size() + "대 운영");
        if (!turnOff.isEmpty()) s.append(", ").append(String.join("·", turnOff)).append(" 계획정지(운휴)");
        if (!turnOn.isEmpty()) s.append(", ").append(String.join("·", turnOn)).append(" 다시 가동");
        if (!p.changes().isEmpty()) s.append(", 경로 ").append(p.changes().size()).append("건 변경");
        if (p.overloaded()) s.append(" · 도장 능력 부족 (병목)");
        else if (turnOff.isEmpty() && turnOn.isEmpty() && p.changes().isEmpty()) s.append(" · 지금 운영이 맞습니다");
        return s.toString();
    }

    /** 판단 근거 (화면·보고서에 그대로) */
    private List<String> reasons(Facts f, PaintPlanner.Plan p, double u) {
        List<String> out = new ArrayList<>();
        out.add("라인 1개의 도장 부하 = 도장 CT " + num(f.paintCt()) + "초 ÷ 라인 박자 " + num(f.lineCt()) + "초 = " + pct(u));
        out.add("도장 1대 상한 " + pct(PaintPlanner.RHO) + " (재공이 쌓이지 않게 15% 여유), 켜진 도장을 끄고 합칠 때는 " + pct(PaintPlanner.RHO_MERGE)
                + " (경계에서 껐다 켰다 하지 않게)");
        int fit = (int) Math.floor(PaintPlanner.RHO / Math.max(u, 1e-9) + 1e-9);
        out.add("도장 1대가 받을 수 있는 라인 = " + fit + "개 (" + pct(u) + " × " + fit + " = " + pct(u * fit) + ")");
        for (Map.Entry<String, String> e : f.boothState().entrySet()) {
            if (!f.usable().contains(e.getKey())) out.add(e.getKey() + "는 " + ("D".equals(e.getValue()) ? "고장" : "통신 끊김") + "이라 배정에서 뺐습니다");
        }
        for (PaintPlanner.Change c : p.changes()) out.add(c.lineId() + "라인 " + c.from() + " → " + c.to());
        if (p.overloaded()) {
            double perHour = 3600.0 / Math.max(f.paintCt(), 1e-9);
            out.add("도장이 시간당 약 " + (int) perHour + "개씩만 칠할 수 있어 가동 라인 생산을 다 받지 못합니다. 라인 수를 줄이거나 도장 복구를 먼저 검토하세요");
        }
        return out;
    }

    /**
     * 제안대로 적용 (P-02 · 보고서 결재 뒤). 순서: 켤 도장의 운휴 끝내기 → 경로 바꾸기 → 끌 도장 운휴 시작.
     * 바뀐 내용을 문장으로 돌려줘요 (없으면 빈 목록)
     */
    /**
     * 새 트랜잭션: 보고서 결재는 확정(커밋)된 뒤에 이걸 불러요 (ReplanService.applyPaint) → 결재가 바꾼 라인 상태를 보고,
     * 여기서 실패해도 결재는 되돌리지 않아요. (JPA 설정이 저장점(NESTED)을 지원하지 않아서 이렇게 나눠요)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public List<String> apply(String reason) {
        PlanView v = plan();
        List<String> done = new ArrayList<>();
        if (!v.changeNeeded()) return done;
        LocalDateTime now = Kst.now();
        for (String pt : v.turnOn()) {
            int n = jdbc.update("UPDATE downtime_event SET ended_at = :now WHERE equipment_id = :pt AND status = 'S' AND ended_at IS NULL",
                    Map.of("now", now, "pt", pt));
            if (n > 0) done.add(pt + " 다시 가동");
        }
        for (PaintPlanner.Change c : v.changes()) {
            monitoringService.changePaintRoute(c.lineId(), new PaintRouteRequest(c.to(), cut("도장 운영 " + reason)));
            done.add(c.lineId() + "라인 도장 " + c.from() + " → " + c.to());
        }
        for (String pt : v.turnOff()) {
            Map<String, Object> p = new HashMap<>();
            p.put("pt", pt);
            p.put("code", STANDBY);
            p.put("now", now);
            jdbc.update("INSERT INTO downtime_event(equipment_id, status, code, started_at, detail) VALUES (:pt, 'S', :code, :now, '물량 적음 운휴 (도장 운영 판단)')", p);
            done.add(pt + " 계획정지 (물량 적음 운휴)");
        }
        log.info("도장 운영 적용 ({}): {}", reason, done);
        return done;
    }

    /** 보고서 문장: 결재 뒤 가동 라인 기준 도장 운영 (바꿀 것이 없으면 null). runningAfter = null이면 지금 상태(같은 트랜잭션에서 바꾼 것 포함) */
    public String previewText(List<String> runningAfter) {
        try {
            PlanView v = runningAfter == null ? plan() : plan(runningAfter);
            if (!v.changeNeeded()) return null;
            List<String> parts = new ArrayList<>();
            for (PaintPlanner.Change c : v.changes()) parts.add(c.lineId() + "라인 " + c.from() + "→" + c.to());
            if (!v.turnOff().isEmpty()) parts.add(String.join("·", v.turnOff()) + " 운휴(계획정지)");
            if (!v.turnOn().isEmpty()) parts.add(String.join("·", v.turnOn()) + " 다시 가동");
            return "도장 운영: 결재 뒤 가동 " + v.running().size() + "개 라인(부하 합 " + pct(v.lineLoad() * v.running().size()) + ") 기준 "
                    + String.join(", ", parts) + (v.overloaded() ? " (도장 능력 부족 주의)" : "") + ". 결재하면 함께 적용합니다.";
        } catch (RuntimeException e) {
            log.warn("도장 운영 미리 보기 실패: {}", e.getMessage());
            return null;
        }
    }

    private List<String> booths() {
        return jdbc.queryForList("SELECT equipment_id FROM equipment WHERE process_id = 'PAINTING' AND is_active = TRUE ORDER BY equipment_id", Map.of(), String.class);
    }

    private double ct(String processIds) {
        BigDecimal ct = jdbc.queryForObject("SELECT MAX(cycle_time_sec) FROM process WHERE process_id IN (" + processIds + ")", Map.of(), BigDecimal.class);
        return ct == null || ct.signum() <= 0 ? 1.0 : ct.doubleValue();
    }

    private LocalDateTime shiftEndAt() {
        LocalDateTime now = Kst.now();
        return "24:00".equals(shiftEnd) ? now.toLocalDate().plusDays(1).atStartOfDay() : now.toLocalDate().atTime(java.time.LocalTime.parse(shiftEnd));
    }

    private static String cut(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
