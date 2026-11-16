package com.gadong.sfaas.ingest.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 출고 검사. result는 OK/NG(비전 판정만). 불량이면 shipped_at 없음 */
@Entity @Table(name = "inspection") @Getter @NoArgsConstructor
public class Inspection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "inspection_id") private Long inspectionId;
    @Column(name = "equipment_id") private String equipmentId;
    @Column(name = "product_id") private String productId;
    @Column(name = "line_id") private String lineId;
    @Column(name = "result") private String result;
    @Column(name = "inspected_at") private LocalDateTime inspectedAt;
    @Column(name = "shipped_at") private LocalDateTime shippedAt;
}
