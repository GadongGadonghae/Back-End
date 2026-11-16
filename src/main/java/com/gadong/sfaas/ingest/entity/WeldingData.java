package com.gadong.sfaas.ingest.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** welding_data: 측정값 한 줄 = 제품 1개. result는 OK/NG (기준 확정 전에는 null) */
@Entity @Table(name = "welding_data") @Getter @NoArgsConstructor
public class WeldingData {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") private Long id;
    @Column(name = "equipment_id") private String equipmentId;
    @Column(name = "measured_at") private LocalDateTime measuredAt;
    @Column(name = "press_force") private Double pressForce;
    @Column(name = "current") private Double current;
    @Column(name = "voltage") private Double voltage;
    @Column(name = "weld_time") private Double weldTime;
    @Column(name = "result") private String result;
}
