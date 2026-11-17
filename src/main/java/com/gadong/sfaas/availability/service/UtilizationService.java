package com.gadong.sfaas.availability.service;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.gadong.sfaas.availability.dto.AvailabilityDtos.*;
import com.gadong.sfaas.global.util.Checks;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.global.util.Periods;
import com.gadong.sfaas.master.service.MasterService;
import com.gadong.sfaas.monitoring.service.ProductionStats;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가동률·OEE·양품률 집계 (U-01~U-06, U-11).
 *
 * <p>가동률 = (계획 가동시간 − 비계획정지) ÷ 계획 가동시간. 계획 가동시간은 조회 구간(오늘이면 지금까지)에서 계획정지(S)를 뺀 시간이고,
 * 계획정지 시간 안에서 난 비계획정지는 손실로 세지 않아요. DC(통신 끊김)는 가동으로 보니 비가동 이력이 없어 따로 처리하지 않아요.
 * 라인별·전체 가동률은 라인 설비(슬리팅·프레스·용접)만 쓰고, 공정별·설비별은 공용 설비(도장·검사)도 포함해요.
 */
@Service
@Transactional(readOnly = true)
public class UtilizationService {

    /** shortcut: 목표 가동률을 담는 테이블이 없어 고정값이에요. 목표를 바꿀 수 있게 하려면 설정 테이블을 추가해요 */
    static final double TARGET = 95.0;
    private static final List<String> PROCESS_ORDER = List.of("SLITTING", "PRESS", "WELDING", "PAINTING", "INSPECTION");

    private record Eq(String id, String lineId, String process) {}

    private record Iv(LocalDateTime start, LocalDateTime end, boolean planned) {}

    /** 계획 가동 초, 비계획정지 초, 비가동 건수(1분 이상) */
    private record T(long planned, long unplanned, int stops) {
        static final T ZERO = new T(0, 0, 0);

        T plus(T o) { return new T(planned + o.planned, unplanned + o.unplanned, stops + o.stops); }

        double actual() { return planned == 0 ? 0 : round1((planned - unplanned) * 100.0 / planned); }
    }

    /** 기간과 겹치는 모든 설비·비가동 구간과 공정 처리 시간 */
    private record Data(List<Eq> eqs, Map<String, List<Iv>> ivs, Map<String, Double> cycle) {

        T time(Eq eq, LocalDateTime bs, LocalDateTime be) {
            LocalDateTime end = Kst.now().isBefore(be) ? Kst.now() : be; // 아직 오지 않은 시간은 계획 가동시간이 아니에요
            if (!end.isAfter(bs)) return T.ZERO;
            List<Iv> all = ivs.getOrDefault(eq.id(), List.of());
            long window = Duration.between(bs, end).getSeconds(), planned = 0, unplanned = 0;
            int stops = 0;
            List<Iv> stopsPlanned = all.stream().filter(Iv::planned).toList();
            for (Iv s : stopsPlanned) planned += overlap(s.start(), s.end(), bs, end);
            for (Iv u : all) {
                if (u.planned()) continue;
                unplanned += overlap(u.start(), u.end(), bs, end);
                for (Iv s : stopsPlanned) { // 계획정지와 겹친 시간은 손실이 아니에요
                    LocalDateTime from = u.start().isAfter(s.start()) ? u.start() : s.start();
                    LocalDateTime to = earlier(u.end(), s.end());
                    unplanned -= overlap(from, to, bs, end);
                }
                if (!u.start().isBefore(bs) && u.start().isBefore(be) && Kst.minutes(u.start(), u.end()) >= 1) stops++;
            }
            return new T(window - planned, Math.max(0, unplanned), stops);
        }

        /** 라인의 시간당 생산 능력(초당): 공정별 (설비 수 ÷ 처리 시간) 중 가장 느린 값 */
        double capacityPerSec(String lineId) {
            Map<String, Integer> count = new HashMap<>();
            for (Eq e : eqs) if (lineId.equals(e.lineId())) count.merge(e.process(), 1, Integer::sum);
            return count.entrySet().stream().filter(e -> cycle.getOrDefault(e.getKey(), 0.0) > 0)
                    .mapToDouble(e -> e.getValue() / cycle.get(e.getKey())).min().orElse(0);
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final MasterService masterService;
    private final ProductionStats stats;

    public UtilizationService(NamedParameterJdbcTemplate jdbc, MasterService masterService, ProductionStats stats) {
        this.jdbc = jdbc;
        this.masterService = masterService;
        this.stats = stats;
    }

    /** U-01 */
    public SummaryResponse summary(LocalDate from, LocalDate to, String lineId) {
        Checks.period(from, to);
        masterService.requireLine(lineId);
        Data d = load(from, to);
        LocalDateTime bs = Kst.start(from), be = Kst.endExclusive(to);
        Map<String, T> byLine = new TreeMap<>();
        Map<String, Integer> eqCount = new HashMap<>();
        T all = T.ZERO;
        double loss = 0;
        for (Eq e : lineEquipments(d, lineId)) {
            T t = d.time(e, bs, be);
            all = all.plus(t);
            byLine.merge(e.lineId(), t, T::plus);
            eqCount.merge(e.lineId(), 1, Integer::sum);
            loss += t.unplanned() * d.capacityPerSec(e.lineId()); // 손실 수량 = 정지 시간 × 라인 생산 능력
        }
        double ideal = 0; // 가동 시간 동안 만들 수 있었던 수량
        for (var e : byLine.entrySet()) {
            ideal += (e.getValue().planned() - e.getValue().unplanned()) / (double) eqCount.get(e.getKey()) * d.capacityPerSec(e.getKey());
        }
        long total = 0, ok = 0;
        for (ProductionStats.Row r : stats.daily(from, to, lineId)) {
            if (r.lineId() == null) continue;
            total += r.total();
            ok += r.ok();
        }
        double availability = all.actual();
        double quality = total == 0 ? 0 : round1(ok * 100.0 / total);
        Double performance = ideal > 0 ? round1(Math.min(100, total * 100 / ideal)) : null;
        Double oee = performance == null ? null : round1(availability * performance * quality / 10000);
        return new SummaryResponse(TARGET, availability, oee, availability, performance, quality,
                all.planned() / 60, all.unplanned() / 60, Math.round(loss));
    }

    /** U-02. 계획 가동시간이 없는 구간(아직 오지 않은 날)은 빼요 */
    public List<TrendResponse> trend(LocalDate from, LocalDate to, String unit, String lineId) {
        Checks.period(from, to);
        Periods.unit(unit);
        masterService.requireLine(lineId);
        Data d = load(from, to);
        List<Eq> eqs = lineEquipments(d, lineId);
        List<TrendResponse> trend = new ArrayList<>();
        for (LocalDate start = Periods.start(from, unit); !start.isAfter(to); start = Periods.next(start, unit)) {
            LocalDate last = Periods.next(start, unit).minusDays(1);
            T t = sum(d, eqs, start.isBefore(from) ? from : start, last.isAfter(to) ? to : last);
            if (t.planned() > 0) trend.add(new TrendResponse(Periods.label(start, unit), TARGET, t.actual()));
        }
        return trend;
    }

    /** U-03. OEE = 가동률 × 속도가동률(실제 처리 수 ÷ 가동 시간 동안 처리할 수 있는 수) × 양품률(공정 OK ÷ 처리 수) */
    public List<ByProcessResponse> byProcess(LocalDate from, LocalDate to, String lineId) {
        Checks.period(from, to);
        masterService.requireLine(lineId);
        Data d = load(from, to);
        LocalDateTime bs = Kst.start(from), be = Kst.endExclusive(to);
        Map<String, T> byProcess = new HashMap<>();
        Map<String, Double> ideal = new HashMap<>();
        for (Eq e : d.eqs()) {
            if (lineId != null && !lineId.equals(e.lineId())) continue;
            T t = d.time(e, bs, be);
            byProcess.merge(e.process(), t, T::plus);
            double cycle = d.cycle().getOrDefault(e.process(), 0.0);
            if (cycle > 0) ideal.merge(e.process(), (t.planned() - t.unplanned()) / cycle, Double::sum);
        }
        Map<String, long[]> rows = stats.processRows(from, to, lineId);
        return PROCESS_ORDER.stream().filter(byProcess::containsKey).map(p -> {
            T t = byProcess.get(p);
            long[] r = rows.getOrDefault(p, new long[2]);
            Double oee = null;
            if (r[0] > 0 && ideal.getOrDefault(p, 0.0) > 0) {
                double performance = Math.min(1, r[0] / ideal.get(p)), quality = (r[0] - r[1]) / (double) r[0];
                oee = round1(t.actual() * performance * quality);
            }
            return new ByProcessResponse(p, t.actual(), oee, t.unplanned() / 60);
        }).toList();
    }

    /** U-04 */
    public List<ByLineResponse> byLine(LocalDate from, LocalDate to) {
        Checks.period(from, to);
        Data d = load(from, to);
        LocalDateTime bs = Kst.start(from), be = Kst.endExclusive(to);
        Map<String, T> byLine = new TreeMap<>();
        for (Eq e : lineEquipments(d, null)) byLine.merge(e.lineId(), d.time(e, bs, be), T::plus);
        return byLine.entrySet().stream().map(e -> new ByLineResponse(e.getKey(), e.getValue().actual(), e.getValue().stops())).toList();
    }

    /** U-05. 낮은 순 */
    public List<ByEquipmentResponse> byEquipment(LocalDate from, LocalDate to, String process, int limit) {
        Checks.period(from, to);
        if (limit < 1 || limit > 100) throw invalid("limit은 1~100입니다");
        Data d = load(from, to);
        LocalDateTime bs = Kst.start(from), be = Kst.endExclusive(to);
        return d.eqs().stream().filter(e -> process == null || process.equalsIgnoreCase(e.process()))
                .map(e -> new ByEquipmentRow(e.id(), d.time(e, bs, be)))
                .filter(r -> r.t().planned() > 0)
                .sorted(Comparator.comparingDouble((ByEquipmentRow r) -> r.t().actual()).thenComparing(ByEquipmentRow::id))
                .limit(limit).map(r -> new ByEquipmentResponse(r.id(), r.t().actual())).toList();
    }

    /** U-06. 월요일부터 순서대로, 계획 가동시간이 있는 요일만 */
    public List<ByWeekdayResponse> byWeekday(LocalDate from, LocalDate to, String lineId) {
        Checks.period(from, to);
        masterService.requireLine(lineId);
        Data d = load(from, to);
        List<Eq> eqs = lineEquipments(d, lineId);
        Map<DayOfWeek, T> byDay = new TreeMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) byDay.merge(day.getDayOfWeek(), sum(d, eqs, day, day), T::plus);
        return byDay.entrySet().stream().filter(e -> e.getValue().planned() > 0)
                .map(e -> new ByWeekdayResponse(e.getKey().name().substring(0, 3), e.getValue().actual(), e.getValue().stops())).toList();
    }

    /** U-11. groupBy=line|paint이면 묶음마다 한 줄이에요 */
    public List<YieldResponse> yield(LocalDate from, LocalDate to, String unit, String groupBy) {
        Checks.period(from, to);
        Periods.unit(unit);
        if (groupBy != null && !"line".equals(groupBy) && !"paint".equals(groupBy)) throw invalid("groupBy는 line 또는 paint입니다");
        Map<String, long[]> groups = new TreeMap<>(); // "기간|묶음" → {생산 수, 양품}
        for (ProductionStats.Row r : stats.daily(from, to, null)) {
            String key = groupBy == null ? "" : "line".equals(groupBy) ? r.lineId() : r.paintId();
            if (key == null) continue;
            long[] g = groups.computeIfAbsent(Periods.label(Periods.start(r.day(), unit), unit) + "|" + key, k -> new long[2]);
            g[0] += r.total();
            g[1] += r.ok();
        }
        return groups.entrySet().stream().map(e -> {
            String[] k = e.getKey().split("\\|", -1);
            long[] g = e.getValue();
            return new YieldResponse(k[0], k[1].isEmpty() ? null : k[1], (int) g[0], (int) g[1], g[0] == 0 ? 0 : round1(g[1] * 100.0 / g[0]));
        }).toList();
    }

    private record ByEquipmentRow(String id, T t) {}

    private static List<Eq> lineEquipments(Data d, String lineId) {
        return d.eqs().stream().filter(e -> e.lineId() != null && (lineId == null || lineId.equals(e.lineId()))).toList();
    }

    private static T sum(Data d, List<Eq> eqs, LocalDate from, LocalDate to) {
        T t = T.ZERO;
        for (Eq e : eqs) t = t.plus(d.time(e, Kst.start(from), Kst.endExclusive(to)));
        return t;
    }

    private Data load(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", Kst.start(from)).addValue("to", Kst.endExclusive(to));
        List<Eq> eqs = jdbc.query("SELECT equipment_id, line_id, process_id FROM equipment WHERE is_active = TRUE ORDER BY equipment_id",
                (rs, n) -> new Eq(rs.getString(1), rs.getString(2), rs.getString(3)));
        Map<String, List<Iv>> ivs = new HashMap<>();
        jdbc.query("SELECT d.equipment_id, d.started_at, d.ended_at, c.is_planned FROM downtime_event d"
                + " JOIN downtime_code c ON c.code = d.code WHERE d.started_at < :to AND (d.ended_at IS NULL OR d.ended_at > :from)", p,
                rs -> {
                    ivs.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Iv(rs.getObject(2, LocalDateTime.class),
                            rs.getObject(3, LocalDateTime.class), rs.getBoolean(4)));
                });
        Map<String, Double> cycle = new HashMap<>();
        jdbc.query("SELECT process_id, cycle_time_sec FROM process", rs -> {
            cycle.put(rs.getString(1), rs.getDouble(2));
        });
        return new Data(eqs, ivs, cycle);
    }

    /** 끝나지 않은 구간(end = null)은 지금까지로 봐요 */
    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        LocalDateTime x = a == null ? Kst.now() : a, y = b == null ? Kst.now() : b;
        return x.isBefore(y) ? x : y;
    }

    private static long overlap(LocalDateTime s, LocalDateTime e, LocalDateTime bs, LocalDateTime be) {
        LocalDateTime start = s.isAfter(bs) ? s : bs;
        LocalDateTime end = earlier(e, be);
        return end.isAfter(start) ? Duration.between(start, end).getSeconds() : 0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
