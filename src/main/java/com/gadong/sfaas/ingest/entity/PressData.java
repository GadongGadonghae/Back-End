package com.gadong.sfaas.ingest.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** press_data: 측정값 한 줄 = 제품 1개. result는 OK/NG (기준 확정 전에는 null) */
@Entity @Table(name = "press_data") @Getter @NoArgsConstructor
public class PressData {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") private Long id;
    @Column(name = "equipment_id") private String equipmentId;
    /** 제품 번호. Node-RED가 넣어요 (값이 없을 수 있음) */
    @Column(name = "product_id", length = 30) private String productId;
    @Column(name = "measured_at") private LocalDateTime measuredAt;
    @Column(name = "pressure_left") private BigDecimal pressureLeft;
    @Column(name = "pressure_right") private BigDecimal pressureRight;
    @Column(name = "press_time") private BigDecimal pressTime;
    @Column(name = "pressure_total") private BigDecimal pressureTotal;
    /** 좌 − 우 압력 차 (PR3 판정 항목) */
    @Column(name = "pressure_diff") private BigDecimal pressureDiff;
    @Column(name = "result") private String result;
}
