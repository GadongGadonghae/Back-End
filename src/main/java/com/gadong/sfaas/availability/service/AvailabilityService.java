package com.gadong.sfaas.availability.service;

import com.gadong.sfaas.availability.dto.AvailabilityDtos.*;
import com.gadong.sfaas.global.response.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 가동률 화면 API 서비스.
 *
 * <p>TODO(BE1): 지금은 API 명세서 v2 예시 값을 돌려주는 stub이에요. 가동률 = (계획 가동시간 − 비계획정지 시간) ÷ 계획 가동시간 × 100 을
 * status_log · downtime 구간으로 계산하도록 바꿔요. 시간당 생산 능력이 정해질 때까지 performance·oee·lossQty 는 null 이에요.
 */
@Service
public class AvailabilityService {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-18T09:30:00+09:00");

    /** U-01 */
    public SummaryResponse summary(LocalDate from, LocalDate to, String lineId) {
        return new SummaryResponse(95.0, 91.4, null, 91.4, null, 91.5, 2880, 248, null);
    }

    /** U-02 */
    public List<TrendResponse> trend(LocalDate from, LocalDate to, String unit, String lineId) {
        return List.of(new TrendResponse("2026-11-17", 95.0, 92.1));
    }

    /** U-03 */
    public List<ByProcessResponse> byProcess(LocalDate from, LocalDate to, String lineId) {
        return List.of(new ByProcessResponse("PRESS", 89.3, null, 132));
    }

    /** U-04 */
    public List<ByLineResponse> byLine(LocalDate from, LocalDate to) {
        return List.of(new ByLineResponse("A", 93.0, 4));
    }

    /** U-05 */
    public List<ByEquipmentResponse> byEquipment(LocalDate from, LocalDate to, String process, int limit) {
        return List.of(new ByEquipmentResponse("C-WD", 78.5));
    }

    /** U-06 */
    public List<ByWeekdayResponse> byWeekday(LocalDate from, LocalDate to, String lineId) {
        return List.of(new ByWeekdayResponse("MON", 96.6, 108));
    }

    /** U-07 */
    public DowntimeSummaryResponse downtimeSummary(LocalDate from, LocalDate to, String groupBy) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("key", "A");
        group.put("totalMin", 298);
        group.put("UP_BREAKDOWN", 45);
        return new DowntimeSummaryResponse("2026-11-17", "2026-11-18", 1616,
                List.of(new DowntimeSummaryResponse.ReasonStat("UP_BREAKDOWN", 175, 12, 10.8),
                        new DowntimeSummaryResponse.ReasonStat("UP_SHORTAGE", 235, 20, 14.5)),
                List.of(group));
    }

    /** U-08: 1분 이상 정지만 비가동 1건으로 세요(minMinutes=1) */
    public PageResponse<DowntimeEventResponse> downtimeEvents(LocalDate from, LocalDate to, String lineId, String process,
                                                              String status, String reasonCode, Boolean inProgress,
                                                              Long actionUserId, String equipmentId, int minMinutes,
                                                              int page, int size) {
        return PageResponse.of(List.of(new DowntimeEventResponse(1, "A", "A-PR1", "PRESS", "D", "UP_BREAKDOWN",
                T0.minusMinutes(40), T0, 40, false, 7L, "홍길동", "금형 교체")));
    }

    /** U-09: 자동 판정된 사유 중 틀린 것만 사람이 수정해요 */
    public DowntimeEventUpdateResponse updateDowntimeEvent(long eventId, DowntimeEventUpdateRequest request) {
        return new DowntimeEventUpdateResponse(eventId, request.reasonCode(), request.actionUserId(),
                request.actionUserId() == null ? null : "홍길동");
    }

    /** U-10 */
    public List<DowntimeTopResponse> downtimeTop(LocalDate from, LocalDate to, int minMinutes, int limit) {
        return List.of(new DowntimeTopResponse("B-PR3", 265, 12));
    }

    /** U-11 */
    public List<YieldResponse> yield(LocalDate from, LocalDate to, String unit, String groupBy) {
        return List.of(new YieldResponse("2026-11-17", 1180, 1121, 95.0));
    }

    /** U-12 */
    public List<StatusSegmentResponse> statusHistory(String equipmentId, LocalDate from, LocalDate to) {
        return List.of(new StatusSegmentResponse("R", T0.minusHours(1), T0.minusMinutes(40), null),
                new StatusSegmentResponse("D", T0.minusMinutes(40), T0, "UP_BREAKDOWN"));
    }
}
