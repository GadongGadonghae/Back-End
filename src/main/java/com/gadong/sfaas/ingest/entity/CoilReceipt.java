package com.gadong.sfaas.ingest.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "coil_receipt") @Getter @NoArgsConstructor
public class CoilReceipt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "receipt_id") private Long receiptId;
    @Column(name = "material_id") private String materialId;
    @Column(name = "received_at") private LocalDateTime receivedAt;
    @Column(name = "product_qty") private Integer productQty;
}
