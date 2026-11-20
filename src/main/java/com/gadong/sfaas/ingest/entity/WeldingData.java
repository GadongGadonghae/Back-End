package com.gadong.sfaas.ingest.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** welding_data: 측정값 한 줄 = 제품 1개. result는 OK/NG (기준 확정 전에는 null) */
@Entity @Table(name = "welding_data") @Getter @NoArgsConstructor
public class WeldingData {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") private Long id;
    @Column(name = "equipment_id") private String equipmentId;
    /** 제품 번호. Node-RED가 넣어요 (값이 없을 수 있음) */
    @Column(name = "product_id", length = 30) private String productId;
    @Column(name = "measured_at") private LocalDateTime measuredAt;
    @Column(name = "press_force") private BigDecimal pressForce;
    @Column(name = "current") private BigDecimal current;
    @Column(name = "voltage") private BigDecimal voltage;
    @Column(name = "weld_time") private BigDecimal weldTime;
    @Column(name = "result") private String result;
}
