package com.gadong.sfaas.monitoring.service;

import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 모니터링 화면 API 서비스.
 *
 * <p>TODO(BE1): 지금은 API 명세서 v2 예시 값을 돌려주는 stub이에요. 실제 값은 status_log, alarm, 공정별 데이터,
 * inspection 테이블에서 읽어 오도록 바꾸고, 판정은 judge 패키지에서 해요.
 */
@Service
public class MonitoringService {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-18T09:30:00+09:00");

    /** M-01 */
    public List<EquipmentStatusResponse> status(String lineId) {
        return List.of(sampleStatus("A-PR1", "A", "PRESS"));
    }

    /** M-02 */
    public List<LineSummaryResponse> linesSummary() {
        return List.of(new LineSummaryResponse("A", 5, 0, 1, 0, "I"));
    }

    /** M-03: interval=raw 는 사이클 값 그대로, 5s 는 조회 때 5초 평균을 계산(저장하지 않음) */
    public MetricsResponse metrics(String equipmentId, LocalDate from, LocalDate to, String metric, String interval) {
        return new MetricsResponse(equipmentId, List.of(new MetricsResponse.Series("pressure1", "bar", 150.0, 285.3,
                List.of(new MetricsResponse.Point(T0, 275.1, "OK"), new MetricsResponse.Point(T0.plusSeconds(5), 287.1, "NG")))));
    }

    /** M-04 */
    public List<AlarmResponse> activeAlarms(String lineId) {
        return List.of(sampleAlarm());
    }

    /** M-05 */
    public PageResponse<AlarmResponse> alarms(LocalDate from, LocalDate to, String equipmentId, int page, int size) {
        return PageResponse.of(List.of(sampleAlarm()));
    }

    /** M-06 */
    public AckResponse ack(long alarmId, AckRequest request) {
        return new AckResponse(alarmId, T0);
    }

    /** M-10 */
    public List<ThresholdResponse> thresholds(String process) {
        return List.of(new ThresholdResponse(1, "WELDING", "current", 0.0, 15.07));
    }

    /** M-11 */
    public ThresholdUpdateResponse updateThreshold(long id, ThresholdUpdateRequest request) {
        return new ThresholdUpdateResponse(id, request.low(), request.high(), T0);
    }

    /** M-07 */
    public ProductionCountsResponse counts(LocalDate date, String lineId, String groupBy) {
        return new ProductionCountsResponse("2026-11-18", 1200, 30, 1170, 28, 1142, 95.2,
                List.of(new ProductionCountsResponse.GroupCount("A", 210, 201, 9)),
                List.of(new ProductionCountsResponse.ProcessCount("PRESS", 1215, 12)));
    }

    /** M-08 */
    public PageResponse<InspectionResponse> inspections(LocalDate from, LocalDate to, String lineId, String paintId,
                                                        String result, int page, int size) {
        return PageResponse.of(List.of(new InspectionResponse(1, "C", "PT1", "OK", T0)));
    }

    /** M-09 (P2) */
    public PaintRouteResponse changePaintRoute(String lineId, PaintRouteRequest request) {
        return new PaintRouteResponse(lineId, request.paintId(), T0);
    }

    /** M-12 */
    public List<EquipmentStatusResponse> processEquipments(String process, String lineId) {
        return List.of(sampleStatus("A-PR1", "A", process));
    }

    /** M-13 */
    public List<WipResponse> wip(String lineId) {
        return List.of(new WipResponse("PRESS", "WELDING", 42));
    }

    /** E-01 */
    public PageResponse<EquipmentDataResponse> equipmentData(LocalDate from, LocalDate to, String lineId, String process,
                                                             String equipmentId, String result, int page, int size) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("pressure1", 275.1);
        values.put("pressure2", 271.3);
        return PageResponse.of(List.of(new EquipmentDataResponse(T0, "A-PR1", "PRESS", "OK", values)));
    }

    private static EquipmentStatusResponse sampleStatus(String equipmentId, String lineId, String process) {
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("pressure1", 287.1);
        last.put("pressure2", 271.0);
        last.put("pressTime", 551);
        return new EquipmentStatusResponse(equipmentId, lineId, process, "D", T0.minusMinutes(18), "UP_BREAKDOWN", last);
    }

    private static AlarmResponse sampleAlarm() {
        return new AlarmResponse(381, "C-WD", "CRITICAL", "용접 전류 상한 초과", T0);
    }
}
