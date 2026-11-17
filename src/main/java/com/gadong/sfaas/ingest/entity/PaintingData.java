package com.gadong.sfaas.ingest.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** painting_data: 측정값 한 줄 = 제품 1개. result는 OK/NG (기준 확정 전에는 null) */
@Entity @Table(name = "painting_data") @Getter @NoArgsConstructor
public class PaintingData {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") private Long id;
    @Column(name = "equipment_id") private String equipmentId;
    @Column(name = "measured_at") private LocalDateTime measuredAt;
    @Column(name = "line_id") private String lineId;
    @Column(name = "ph") private BigDecimal ph;
    @Column(name = "temperature") private BigDecimal temperature;
    @Column(name = "current") private BigDecimal current;
    @Column(name = "voltage") private BigDecimal voltage;
    @Column(name = "result") private String result;
}
