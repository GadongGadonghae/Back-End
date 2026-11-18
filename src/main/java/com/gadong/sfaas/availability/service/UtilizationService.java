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
 * 가동률·OEE·양품률 집계 (U-01~U-06, U-11). OEE 계산은 이 클래스 한 곳({@link Acc})에서만 하고 모든 API가 같이 써요.
 *
 * <ul>
 *   <li>부하시간 = 전체(조회 구간, 오늘이면 지금까지) − S(계획정지) 시간</li>
 *   <li>시간가동률(availability) = (부하시간 − I·D 시간) ÷ 부하시간. DC(통신 끊김)는 가동으로 봐요.
 *       계획정지 안에서 난 I·D는 손실로 세지 않아요</li>
 *   <li>성능가동률(performance) = 표준 CT(process.cycle_time_sec) × 생산수량 ÷ 가동시간, 최대 100%</li>
 *   <li>양품률(quality, OEE용) = 공정 OK 수 ÷ 공정 처리(판정된) 수. 공정·설비·라인 모두 같은 기준.
 *       검사 공정은 inspection_result의 OK ÷ 검사 수</li>
 *   <li>OEE = 시간가동률 × 성능가동률 × 양품률</li>
 *   <li>라인 수율(lineYield) = 출고 양품(inspection_result OK) ÷ 슬리팅 투입 수. OEE와 별개 지표예요</li>
 * </ul>
 * 묶음(라인·공정·전체)은 설비별 시간·수량을 먼저 더한 뒤 비율을 구해요. 라인별·전체는 라인 설비(슬리팅·프레스·용접)만,
 * 공정별(lineId 없을 때)·설비별은 공용 설비(도장·검사)도 포함해요.
 *
 * <p>출처: Nakajima, S. "Introduction to TPM"(1988), ISO 22400-2:2014 (KPI: Availability, Effectiveness, Quality ratio, OEE)
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

    /** 생산수량, 판정된 수, OK 수 */
    private record Qty(long produced, long judged, long ok) {
        static final Qty ZERO = new Qty(0, 0, 0);

        Qty plus(Qty o) { return new Qty(produced + o.produced, judged + o.judged, ok + o.ok); }
    }

    /** OEE 재료: 시간(T) + 표준 CT × 생산수량(초) + 판정 수·OK 수. 더한 뒤 비율을 구해요 */
    private record Acc(T t, double standardSec, long judged, long ok) {
        static final Acc ZERO = new Acc(T.ZERO, 0, 0, 0);

        Acc plus(Acc o) { return new Acc(t.plus(o.t), standardSec + o.standardSec, judged + o.judged, ok + o.ok); }

        private long run() { return t.planned() - t.unplanned(); }

        double availability() { return t.actual(); }

        /** 가동시간이 없으면 null */
        Double performance() { return run() <= 0 ? null : round1(perf() * 100); }

        /** 판정된 제품이 없으면 null */
        Double quality() { return judged == 0 ? null : round1(ok * 100.0 / judged); }

        /** 세 요소 중 하나라도 없으면 null. 반올림 전 값끼리 곱해요 */
        Double oee() {
            if (t.planned() == 0 || run() <= 0 || judged == 0) return null;
            return round1(run() / (double) t.planned() * perf() * ok / judged * 100);
        }

        private double perf() { return Math.min(1, standardSec / run()); }
    }

    /** 기간과 겹치는 모든 설비·비가동 구간, 공정 표준 CT, 설비·일별 수량, 라인·일별 출고 양품 */
    private record Data(List<Eq> eqs, Map<String, List<Iv>> ivs, Map<String, Double> cycle,
                        Map<String, Map<LocalDate, Qty>> qty, Map<String, Map<LocalDate, Long>> shipOk) {

        Acc acc(Eq eq, LocalDateTime bs, LocalDateTime be) {
            Qty q = qty(eq.id(), bs, be);
            return new Acc(time(eq, bs, be), cycle.getOrDefault(eq.process(), 0.0) * q.produced(), q.judged(), q.ok());
        }

        /** [bs, be) 안의 날짜만 더해요 */
        Qty qty(String equipmentId, LocalDateTime bs, LocalDateTime be) {
            Qty sum = Qty.ZERO;
            for (var e : qty.getOrDefault(equipmentId, Map.of()).entrySet()) {
                if (!e.getKey().isBefore(bs.toLocalDate()) && e.getKey().isBefore(be.toLocalDate())) sum = sum.plus(e.getValue());
            }
            return sum;
        }

        /** 라인 수율 = 출고 양품 ÷ 슬리팅 투입 수. lineId가 null이면 전체 라인. 투입이 없으면 null */
        Double lineYield(String lineId, LocalDateTime bs, LocalDateTime be) {
            long input = eqs.stream().filter(e -> "SLITTING".equals(e.process()) && e.lineId() != null
                            && (lineId == null || lineId.equals(e.lineId())))
                    .mapToLong(e -> qty(e.id(), bs, be).produced()).sum();
            long ok = 0;
            for (var line : shipOk.entrySet()) {
                if (lineId != null && !lineId.equals(line.getKey())) continue;
                for (var day : line.getValue().entrySet()) {
                    if (!day.getKey().isBefore(bs.toLocalDate()) && day.getKey().isBefore(be.toLocalDate())) ok += day.getValue();
                }
            }
            return input == 0 ? null : round1(ok * 100.0 / input);
        }

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

    /** {테이블, 시각 컬럼}: 설비별 생산수량·판정 수·OK 수를 세는 곳 */
    private static final List<String[]> QTY_SOURCES = List.of(new String[]{"slitting_data", "measured_at"},
            new String[]{"press_data", "measured_at"}, new String[]{"welding_data", "measured_at"},
            new String[]{"painting_data", "measured_at"}, new String[]{"inspection_result", "inspected_at"});

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
        Acc all = Acc.ZERO;
        double loss = 0;
        for (Eq e : lineEquipments(d, lineId)) {
            Acc a = d.acc(e, bs, be);
            all = all.plus(a);
            loss += a.t().unplanned() * d.capacityPerSec(e.lineId()); // 손실 수량 = 정지 시간 × 라인 생산 능력
        }
        T t = all.t();
        return new SummaryResponse(TARGET, all.availability(), all.oee(), all.availability(), all.performance(),
                all.quality() == null ? 0 : all.quality(), t.planned() / 60, t.unplanned() / 60, Math.round(loss),
                d.lineYield(lineId, bs, be));
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
            Acc a = sum(d, eqs, start.isBefore(from) ? from : start, last.isAfter(to) ? to : last);
            if (a.t().planned() > 0) {
                trend.add(new TrendResponse(Periods.label(start, unit), TARGET, a.availability(), a.oee(), a.availability(),
                        a.performance(), a.quality()));
            }
        }
        return trend;
    }

    /** U-03. lineId가 없으면 공용 설비(도장·검사)도 포함해요 */
    public List<ByProcessResponse> byProcess(LocalDate from, LocalDate to, String lineId) {
        Checks.period(from, to);
        masterService.requireLine(lineId);
        Data d = load(from, to);
        LocalDateTime bs = Kst.start(from), be = Kst.endExclusive(to);
        Map<String, Acc> byProcess = new HashMap<>();
        for (Eq e : d.eqs()) {
            if (lineId != null && !lineId.equals(e.lineId())) continue;
            byProcess.merge(e.process(), d.acc(e, bs, be), Acc::plus);
        }
        return PROCESS_ORDER.stream().filter(byProcess::containsKey).map(p -> {
            Acc a = byProcess.get(p);
            return new ByProcessResponse(p, a.availability(), a.oee(), a.t().unplanned() / 60);
        }).toList();
    }

    /** U-04 */
    public List<ByLineResponse> byLine(LocalDate from, LocalDate to) {
        Checks.period(from, to);
        Data d = load(from, to);
        LocalDateTime bs = Kst.start(from), be = Kst.endExclusive(to);
        Map<String, Acc> byLine = new TreeMap<>();
        for (Eq e : lineEquipments(d, null)) byLine.merge(e.lineId(), d.acc(e, bs, be), Acc::plus);
        return byLine.entrySet().stream().map(e -> new ByLineResponse(e.getKey(), e.getValue().availability(),
                e.getValue().t().stops(), e.getValue().oee(), d.lineYield(e.getKey(), bs, be))).toList();
    }

    /** U-05. 가동률 낮은 순. limit이 없으면 전부 */
    public List<ByEquipmentResponse> byEquipment(LocalDate from, LocalDate to, String process, String lineId, Integer limit) {
        Checks.period(from, to);
        if (limit != null && (limit < 1 || limit > 100)) throw invalid("limit은 1~100입니다");
        masterService.requireLine(lineId);
        Data d = load(from, to);
        LocalDateTime bs = Kst.start(from), be = Kst.endExclusive(to);
        return d.eqs().stream().filter(e -> process == null || process.equalsIgnoreCase(e.process()))
                .filter(e -> lineId == null || lineId.equals(e.lineId()))
                .map(e -> new ByEquipmentRow(e.id(), d.acc(e, bs, be)))
                .filter(r -> r.a().t().planned() > 0)
                .sorted(Comparator.comparingDouble((ByEquipmentRow r) -> r.a().availability()).thenComparing(ByEquipmentRow::id))
                .limit(limit == null ? Long.MAX_VALUE : limit)
                .map(r -> new ByEquipmentResponse(r.id(), r.a().availability(), r.a().oee())).toList();
    }

    /** U-06. 월요일부터 순서대로, 계획 가동시간이 있는 요일만 */
    public List<ByWeekdayResponse> byWeekday(LocalDate from, LocalDate to, String lineId) {
        Checks.period(from, to);
        masterService.requireLine(lineId);
        Data d = load(from, to);
        List<Eq> eqs = lineEquipments(d, lineId);
        Map<DayOfWeek, T> byDay = new TreeMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) byDay.merge(day.getDayOfWeek(), sum(d, eqs, day, day).t(), T::plus);
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

    private record ByEquipmentRow(String id, Acc a) {}

    private static List<Eq> lineEquipments(Data d, String lineId) {
        return d.eqs().stream().filter(e -> e.lineId() != null && (lineId == null || lineId.equals(e.lineId()))).toList();
    }

    private static Acc sum(Data d, List<Eq> eqs, LocalDate from, LocalDate to) {
        Acc a = Acc.ZERO;
        for (Eq e : eqs) a = a.plus(d.acc(e, Kst.start(from), Kst.endExclusive(to)));
        return a;
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
        Map<String, Map<LocalDate, Qty>> qty = new HashMap<>();
        for (String[] t : QTY_SOURCES) {
            String day = "CAST(" + t[1] + " AS DATE)";
            jdbc.query("SELECT equipment_id, " + day + ", COUNT(*), COUNT(result),"
                    + " COALESCE(SUM(CASE WHEN result = 'OK' THEN 1 ELSE 0 END), 0) FROM " + t[0]
                    + " WHERE " + t[1] + " >= :from AND " + t[1] + " < :to GROUP BY equipment_id, " + day, p, rs -> {
                qty.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                        .merge(rs.getObject(2, LocalDate.class), new Qty(rs.getLong(3), rs.getLong(4), rs.getLong(5)), Qty::plus);
            });
        }
        Map<String, Map<LocalDate, Long>> shipOk = new HashMap<>();
        jdbc.query("SELECT line_id, CAST(inspected_at AS DATE), COUNT(*) FROM inspection_result"
                + " WHERE result = 'OK' AND line_id IS NOT NULL AND inspected_at >= :from AND inspected_at < :to"
                + " GROUP BY line_id, CAST(inspected_at AS DATE)", p, rs -> {
            shipOk.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getObject(2, LocalDate.class), rs.getLong(3));
        });
        return new Data(eqs, ivs, cycle, qty, shipOk);
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
