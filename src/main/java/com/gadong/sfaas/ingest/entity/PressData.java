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
    @Column(name = "measured_at") private LocalDateTime measuredAt;
    @Column(name = "pressure_left") private BigDecimal pressureLeft;
    @Column(name = "pressure_right") private BigDecimal pressureRight;
    @Column(name = "press_time") private BigDecimal pressTime;
    @Column(name = "pressure_total") private BigDecimal pressureTotal;
    @Column(name = "result") private String result;
}
