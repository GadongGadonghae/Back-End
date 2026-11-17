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
    @Column(name = "approved_by") private Integer approvedBy;
    @Column(name = "approved_at") private LocalDateTime approvedAt;

    /** R-03. 이미 승인된 보고서는 처음 승인 기록을 유지해요 */
    public void approve(Integer userId, LocalDateTime at) {
        if (Boolean.TRUE.equals(approved)) return;
        this.approved = true;
        this.approvedBy = userId;
        this.approvedAt = at;
    }
}
