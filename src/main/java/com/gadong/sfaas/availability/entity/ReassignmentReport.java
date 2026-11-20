package com.gadong.sfaas.availability.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 계획수정(재배정) 보고서. v0.3.10: 계산 기준(kind·base_at·CT·계획·실적·미달)과 AI 문장(ai_content, JSON 글자)을 같이 저장해요 */
@Entity @Table(name = "reassignment_report") @Getter @NoArgsConstructor
public class ReassignmentReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "report_id") private Long reportId;
    @Column(name = "downtime_id") private Long downtimeId;
    @Column(name = "loss_qty") private Integer lossQty;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "approved") private Boolean approved;
    @Column(name = "approved_by") private Integer approvedBy;
    @Column(name = "approved_at") private LocalDateTime approvedAt;
    @Column(name = "created_by") private Integer createdBy;
    /** line: 라인 안 설비 → 라인 전체 정지 · paint: 도장 1대 → 다른 1대 · vision: 대체 없음 */
    @Column(name = "kind", length = 10) private String kind;
    /** 계산 기준 시각 (오늘 건은 만든 시각, 지난 날 건은 고장 시작 시각) */
    @Column(name = "base_at") private LocalDateTime baseAt;
    @Column(name = "ct_sec", precision = 6, scale = 2) private BigDecimal ctSec;
    @Column(name = "stopped_plan") private Integer stoppedPlan;
    @Column(name = "stopped_done") private Integer stoppedDone;
    @Column(name = "target_qty") private Integer targetQty;
    @Column(name = "unmet_qty") private Integer unmetQty;
    @Column(name = "ai_content", columnDefinition = "TEXT") private String aiContent;

    public ReassignmentReport(Long downtimeId, int lossQty, LocalDateTime createdAt, Integer createdBy, String kind, LocalDateTime baseAt,
                              BigDecimal ctSec, int stoppedPlan, int stoppedDone, Integer targetQty, int unmetQty, String aiContent) {
        this.downtimeId = downtimeId;
        this.lossQty = lossQty;
        this.createdAt = createdAt;
        this.approved = false;
        this.createdBy = createdBy;
        this.kind = kind;
        this.baseAt = baseAt;
        this.ctSec = ctSec;
        this.stoppedPlan = stoppedPlan;
        this.stoppedDone = stoppedDone;
        this.targetQty = targetQty;
        this.unmetQty = unmetQty;
        this.aiContent = aiContent;
    }
}
