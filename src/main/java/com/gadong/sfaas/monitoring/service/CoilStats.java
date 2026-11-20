package com.gadong.sfaas.monitoring.service;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.CoilTodayResponse;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.CoilTodayResponse.CoilReceiptItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** M-16 코일 입고 현황 (오늘 입고 롤·개수, 슬리팅 앞 재고, 투입 수, 입고 목록) */
@Component
@Transactional(readOnly = true)
public class CoilStats {
    /** 1롤 = 제품 20개 (DB 결정, coil_receipt.product_qty 기본값) */
    static final int ROLL_QTY = 20;

    // 슬리팅에 들어간 수 = 슬리팅 끝난 수(slitting_data, 시각 색인) + 지금 슬리팅 안에 있는 수(product_flow 처리 중)
    // 11/20: product_flow 전체(100만 줄)에서 슬리팅 줄을 세면 0.2~1초 → 이렇게 세면 40ms. 끝날 때 두 테이블에 같이 써서 수가 같아요
    private static final String SLITTING_IN = "SELECT (SELECT COUNT(*) FROM slitting_data WHERE measured_at >= :from AND measured_at < :to)"
            + " + (SELECT COUNT(*) FROM product_flow f WHERE f.ended_at IS NULL AND f.started_at >= :from AND f.started_at < :to"
            + " AND f.equipment_id IN (SELECT equipment_id FROM equipment WHERE process_id = 'SLITTING'))";

    private final NamedParameterJdbcTemplate jdbc;

    public CoilStats(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CoilTodayResponse today(LocalDate date) {
        LocalDate day = date == null ? Kst.now().toLocalDate() : date;
        LocalDateTime from = Kst.start(day);
        LocalDateTime to = Kst.endExclusive(day);
        // 재고는 그날 끝까지(오늘이면 지금까지) 누적: 전날 남은 코일도 재고예요
        LocalDateTime cut = to.isAfter(Kst.now()) ? Kst.now().plusSeconds(1) : to;

        List<CoilReceiptItem> receipts = jdbc.query(
                "SELECT material_id, received_at, product_qty FROM coil_receipt WHERE received_at >= :from AND received_at < :to ORDER BY received_at, receipt_id",
                Map.of("from", from, "to", to),
                (rs, n) -> new CoilReceiptItem(rs.getString(1), Kst.of(Kst.local(rs.getObject(2))), rs.getInt(3)));
        int receivedQty = receipts.stream().mapToInt(CoilReceiptItem::productQty).sum();

        long receivedUntil = jdbc.queryForObject("SELECT COALESCE(SUM(product_qty), 0) FROM coil_receipt WHERE received_at < :cut",
                Map.of("cut", cut), Long.class);
        long enteredUntil = jdbc.queryForObject(SLITTING_IN, Map.of("from", LocalDateTime.of(1970, 1, 1, 0, 0), "to", cut), Long.class);
        long inputQty = jdbc.queryForObject(SLITTING_IN, Map.of("from", from, "to", cut), Long.class);

        int stockQty = (int) Math.max(0, receivedUntil - enteredUntil);
        double stockRolls = Math.round(stockQty * 10.0 / ROLL_QTY) / 10.0;
        return new CoilTodayResponse(day.toString(), receipts.size(), receivedQty, stockQty, stockRolls, (int) inputQty, receipts);
    }
}
