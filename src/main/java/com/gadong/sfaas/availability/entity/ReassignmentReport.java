package com.gadong.sfaas.availability.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "reassignment_report") @Getter @NoArgsConstructor
public class ReassignmentReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "report_id") private Long reportId;
    @Column(name = "downtime_id") private Long downtimeId;
    @Column(name = "loss_qty") private Integer lossQty;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "approved") private Boolean approved;
    @Column(name = "approved_by") private Long approvedBy;
    @Column(name = "approved_at") private LocalDateTime approvedAt;
}
