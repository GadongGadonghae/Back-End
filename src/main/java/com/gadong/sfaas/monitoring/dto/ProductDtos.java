package com.gadong.sfaas.monitoring.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 제품 추적(P-01·P-02), 공정 불량 제품 목록(P-03), 시간대별 생산(G-01 unit=hour) */
public final class ProductDtos {
    private ProductDtos() {}

    /** G-01 unit=hour. cumulativeGood = from부터 그 구간까지 good 누적(라인별) */
    public record HourlySummaryResponse(OffsetDateTime period, String lineId, int inspected, int good, int processNg, int cumulativeGood) {}

    /** P-01. finalResult = OK(비전 OK) / NG(공정 NG 또는 비전 NG) / IN_PROGRESS */
    public record ProductResponse(String productId, String lineId, OffsetDateTime inputAt, String finalResult,
                                  String scrappedProcess, String scrappedEquipmentId, OffsetDateTime finishedAt) {}

    /** P-02. 프레스는 PR1~PR4를 단계 4개로 나눠요 */
    public record ProductDetailResponse(String productId, String lineId, String finalResult, String scrappedProcess,
                                        String scrappedEquipmentId, List<Step> steps) {
        public record Step(String process, String equipmentId, OffsetDateTime at, Map<String, Object> values, String result) {}
    }

    /** P-03. 기준을 벗어난 항목이 없으면(공정 중단 등) itemName부터 limit까지 null */
    public record ProcessNgResponse(String productId, String lineId, String process, String equipmentId, OffsetDateTime at,
                                    String itemName, BigDecimal measuredValue, BigDecimal lowerLimit, BigDecimal upperLimit, String reason) {}
}
