package com.gadong.sfaas.availability.service;

import static com.gadong.sfaas.global.util.Checks.invalid;

import com.gadong.sfaas.availability.dto.AvailabilityDtos.*;
import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.global.util.Checks;
import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.global.util.Sql;
import com.gadong.sfaas.judge.entity.Downtime;
import com.gadong.sfaas.judge.repository.DowntimeRepository;
import com.gadong.sfaas.master.repository.DowntimeCodeRepository;
import com.gadong.sfaas.master.service.MasterService;
import com.gadong.sfaas.master.service.UserLookup;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가동률 화면 API 서비스. 비가동(U-07~U-10)과 상태 타임라인(U-12)을 downtime_event · equipment_status_history에서 읽어요.
 * 가동률·OEE·양품률(U-01~U-06, U-11)은 {@link UtilizationService}가 해요.
 */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    /** 조회 기간과 겹치는 비가동 구간. 조치 담당자 이름은 users(auth 소유) 테이블에서 가져와요 */
    private static final String DOWNTIME_SELECT = """
            SELECT d.downtime_id, d.equipment_id, e.line_id, e.process_id, d.status, d.code,
                   d.started_at, d.ended_at, d.detail, d.action_memo, d.action_user_id, d.action_at, u.user_name
            FROM downtime_event d
            JOIN equipment e ON e.equipment_id = d.equipment_id
            JOIN downtime_code c ON c.code = d.code
            LEFT JOIN users u ON u.user_id = d.action_user_id
            WHERE d.started_at < :to AND (d.ended_at IS NULL OR d.ended_at > :from)""";

    private record Stop(long id, String equipmentId, String lineId, String process, String status, String code,
                        LocalDateTime start, LocalDateTime end, String detail, String memo, Integer actionUserId,
                        LocalDateTime actionAt, String actionUserName) {
        long minutes() {
            return Kst.minutes(start, end);
        }

        /** 조회 기간 [from, to) 안에 들어간 길이(초) */
        long secondsWithin(LocalDateTime from, LocalDateTime to) {
            LocalDateTime s = start.isBefore(from) ? from : start;
            LocalDateTime e = end == null ? Kst.now() : end;
            if (e.isAfter(to)) e = to;
            return e.isAfter(s) ? Duration.between(s, e).getSeconds() : 0;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final MasterService masterService;
    private final UserLookup users;
    private final DowntimeRepository downtimeRepository;
    private final DowntimeCodeRepository downtimeCodeRepository;

    public AvailabilityService(NamedParameterJdbcTemplate jdbc, MasterService masterService, UserLookup users,
                               DowntimeRepository downtimeRepository, DowntimeCodeRepository downtimeCodeRepository) {
        this.jdbc = jdbc;
        this.masterService = masterService;
        this.users = users;
        this.downtimeRepository = downtimeRepository;
        this.downtimeCodeRepository = downtimeCodeRepository;
    }

    /**
     * U-07: 비계획정지(is_planned = FALSE)만 모아요. 시간은 조회 기간 안에 들어간 만큼, 건수는 1분 이상인 구간만 세요.
     * byGroup은 라인 또는 공정별이고, groupBy=line이면 라인이 없는 공용 설비(도장·검사)는 빠져요.
     */
    public DowntimeSummaryResponse downtimeSummary(LocalDate from, LocalDate to, String groupBy) {
        Checks.period(from, to);
        if (!"line".equals(groupBy) && !"process".equals(groupBy)) throw invalid("groupBy는 line 또는 process입니다");
        LocalDateTime f = Kst.start(from), t = Kst.endExclusive(to);
        List<Stop> stops = stops(new StringBuilder(" AND c.is_planned = FALSE"), Sql.period(from, to));

        long totalSec = 0;
        Map<String, long[]> byReason = new TreeMap<>(); // {초, 건수}
        Map<String, Map<String, Long>> byGroup = new TreeMap<>(); // key → {코드 → 초}
        for (Stop s : stops) {
            long sec = s.secondsWithin(f, t);
            totalSec += sec;
            long[] r = byReason.computeIfAbsent(s.code(), k -> new long[2]);
            r[0] += sec;
            if (s.minutes() >= 1) r[1]++;
            String key = "line".equals(groupBy) ? s.lineId() : s.process();
            if (key != null) byGroup.computeIfAbsent(key, k -> new TreeMap<>()).merge(s.code(), sec, Long::sum);
        }

        long total = totalSec;
        List<DowntimeSummaryResponse.ReasonStat> reasons = byReason.entrySet().stream()
                .map(e -> new DowntimeSummaryResponse.ReasonStat(e.getKey(), e.getValue()[0] / 60, (int) e.getValue()[1],
                        total == 0 ? 0 : Math.round(e.getValue()[0] * 1000.0 / total) / 10.0))
                .toList();
        List<Map<String, Object>> groups = new ArrayList<>();
        byGroup.forEach((key, codes) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("key", key);
            g.put("totalMin", codes.values().stream().mapToLong(Long::longValue).sum() / 60);
            codes.forEach((code, sec) -> g.put(code, sec / 60));
            groups.add(g);
        });
        return new DowntimeSummaryResponse(from.toString(), to.toString(), totalSec / 60, reasons, groups);
    }

    /** U-08 */
    public PageResponse<DowntimeEventResponse> downtimeEvents(LocalDate from, LocalDate to, String lineId, String process,
                                                              String status, String reasonCode, Boolean inProgress,
                                                              Integer actionUserId, String equipmentId, int minMinutes,
                                                              int page, int size) {
        Checks.period(from, to);
        Checks.page(page, size);
        if (minMinutes < 0) throw invalid("minMinutes는 0 이상입니다");
        masterService.requireLine(lineId);
        MapSqlParameterSource p = Sql.period(from, to);
        StringBuilder where = new StringBuilder();
        Sql.eq(where, p, "e.line_id", "lineId", lineId);
        Sql.eq(where, p, "e.process_id", "process", process == null ? null : process.toUpperCase());
        Sql.eq(where, p, "d.status", "status", status);
        Sql.eq(where, p, "d.code", "reasonCode", reasonCode);
        Sql.eq(where, p, "d.action_user_id", "actionUserId", actionUserId);
        Sql.eq(where, p, "d.equipment_id", "eq", equipmentId);
        if (inProgress != null) where.append(inProgress ? " AND d.ended_at IS NULL" : " AND d.ended_at IS NOT NULL");

        // shortcut: minMinutes 필터와 페이지 나누기를 메모리에서 해요. 기간 안 비가동이 수만 건을 넘으면 SQL로 옮겨요
        // 진행 중인 건(D·I·S)은 1분이 안 돼도 바로 보여요 (알람 이력에 발생 즉시 "진행 중"으로, 11/20). minMinutes는 끝난 건에만 적용
        List<Stop> stops = stops(where, p).stream()
                .filter(s -> s.end() == null || s.minutes() >= minMinutes).toList();
        List<DowntimeEventResponse> content = stops.stream().skip((long) (page - 1) * size).limit(size)
                .map(AvailabilityService::toResponse)
                .toList();
        return new PageResponse<>(content, stops.size(), page, size);
    }

    private static DowntimeEventResponse toResponse(Stop s) {
        return new DowntimeEventResponse(s.id(), s.lineId(), s.equipmentId(), s.process(), s.status(), s.code(),
                Kst.of(s.start()), Kst.of(s.end()), s.minutes(), s.end() == null, s.detail(), s.actionUserId(),
                s.actionUserName(), Kst.of(s.actionAt()), s.memo() == null ? "" : s.memo());
    }

    /**
     * U-09: reasonCode·memo·actionUserId 중 보낸 값만 바꿔요.
     * 응답은 바뀐 뒤의 비가동 1건 전체(U-08 items와 같은 모양)예요 → 프론트가 목록을 다시 찾지 않아도 돼요 (11/19).
     * 예전 응답 칸(eventId·reasonCode·actionUserId·actionUserName)은 모두 그대로 들어 있어요
     */
    @Transactional
    public DowntimeEventResponse updateDowntimeEvent(long eventId, DowntimeEventUpdateRequest request) {
        if (request == null) throw invalid("요청 본문이 필요합니다");
        Downtime downtime = downtimeRepository.findById(eventId).orElseThrow(() ->
                new BusinessException(ErrorCode.DOWNTIME_NOT_FOUND, "비가동 이력 " + eventId + "를 찾을 수 없습니다"));
        if (request.reasonCode() != null && !downtimeCodeRepository.existsById(request.reasonCode())) {
            throw invalid("비가동 사유 코드 " + request.reasonCode() + "가 없습니다");
        }
        if (request.memo() != null && request.memo().length() > 500) throw invalid("memo는 500자 이하입니다");
        if (request.actionUserId() != null && !users.exists(request.actionUserId())) {
            throw invalid("사용자 " + request.actionUserId() + "를 찾을 수 없습니다");
        }
        downtime.correct(request.reasonCode(), request.memo(), request.actionUserId(), Kst.now());
        downtimeRepository.flush(); // 아래 JDBC 조회가 바뀐 값을 읽게
        MapSqlParameterSource p = new MapSqlParameterSource("id", eventId)
                .addValue("from", LocalDateTime.of(1970, 1, 1, 0, 0)).addValue("to", LocalDateTime.of(9999, 12, 31, 0, 0));
        return stops(new StringBuilder(" AND d.downtime_id = :id"), p).stream().findFirst().map(AvailabilityService::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOWNTIME_NOT_FOUND, "비가동 이력 " + eventId + "를 찾을 수 없습니다"));
    }

    /** U-10: 장비고장(UP_BREAKDOWN) 중 minMinutes 이상인 구간을 설비별로 합쳐 긴 순으로 */
    public List<DowntimeTopResponse> downtimeTop(LocalDate from, LocalDate to, int minMinutes, int limit) {
        Checks.period(from, to);
        if (minMinutes < 0 || limit < 1 || limit > 100) throw invalid("minMinutes는 0 이상, limit은 1~100입니다");
        Map<String, long[]> byEquipment = new LinkedHashMap<>(); // {분, 건수}
        for (Stop s : stops(new StringBuilder(" AND d.code = 'UP_BREAKDOWN'"), Sql.period(from, to))) {
            long m = s.minutes();
            if (m < minMinutes) continue;
            long[] v = byEquipment.computeIfAbsent(s.equipmentId(), k -> new long[2]);
            v[0] += m;
            v[1]++;
        }
        return byEquipment.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(limit)
                .map(e -> new DowntimeTopResponse(e.getKey(), e.getValue()[0], (int) e.getValue()[1]))
                .toList();
    }

    /** U-12: equipment_status_history 구간. R·DC가 아닌 구간의 사유는 downtime_id로 연결된 비가동 이력에서 가져와요 */
    public List<StatusSegmentResponse> statusHistory(String equipmentId, LocalDate from, LocalDate to) {
        Checks.period(from, to);
        masterService.equipment(equipmentId);
        MapSqlParameterSource p = Sql.period(from, to).addValue("eq", equipmentId);
        return jdbc.query("""
                SELECT s.status, s.started_at, s.ended_at, d.code
                FROM equipment_status_history s
                LEFT JOIN downtime_event d ON d.downtime_id = s.downtime_id
                WHERE s.equipment_id = :eq AND s.started_at < :to AND (s.ended_at IS NULL OR s.ended_at > :from)
                ORDER BY s.started_at""", p, (rs, n) -> {
            String status = rs.getString("status").trim();
            return new StatusSegmentResponse(status, Kst.of(rs.getObject("started_at", LocalDateTime.class)),
                    Kst.of(rs.getObject("ended_at", LocalDateTime.class)), rs.getString("code"));
        });
    }

    private List<Stop> stops(StringBuilder where, MapSqlParameterSource p) {
        return jdbc.query(DOWNTIME_SELECT + where + " ORDER BY d.started_at DESC, d.downtime_id DESC", p, (rs, n) ->
                new Stop(rs.getLong("downtime_id"), rs.getString("equipment_id"), rs.getString("line_id"),
                        rs.getString("process_id"), rs.getString("status").trim(), rs.getString("code"),
                        rs.getObject("started_at", LocalDateTime.class), rs.getObject("ended_at", LocalDateTime.class),
                        rs.getString("detail"), rs.getString("action_memo"), rs.getObject("action_user_id", Integer.class),
                        rs.getObject("action_at", LocalDateTime.class), rs.getString("user_name")));
    }
}
