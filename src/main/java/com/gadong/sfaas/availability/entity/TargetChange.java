package com.gadong.sfaas.availability.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공장 목표 변경 기록 (11/20, v0.3.16). 공장장이 오늘 목표를 올리면 이전·새 목표를 남겨요.
 * 늘어난 수량(after − 라인 목표 합)은 "추가 주문 · 배정 대기"로, 생산관리팀장이 추가 주문 배정 보고서로 라인에 나누고
 * 공장장이 결재하면 라인 목표에 들어가요 (그때 closedAt). 같은 날 다시 올리면 이전 기록은 닫고 새로 남겨요.
 */
@Entity @Table(name = "target_change") @Getter @NoArgsConstructor
public class TargetChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "change_id") private Long changeId;
    @Column(name = "target_date", nullable = false) private LocalDate targetDate;
    @Column(name = "before_qty", nullable = false) private Integer beforeQty;
    @Column(name = "after_qty", nullable = false) private Integer afterQty;
    @Column(name = "changed_by") private Integer changedBy;
    @Column(name = "changed_at", nullable = false) private LocalDateTime changedAt;
    /** 이 변경으로 만든 추가 주문 배정 보고서 (마지막 것) */
    @Column(name = "report_id") private Long reportId;
    /** 결재로 라인 목표에 들어갔거나, 다시 바뀌어 대신한 기록이 생긴 시각 */
    @Column(name = "closed_at") private LocalDateTime closedAt;

    public TargetChange(LocalDate targetDate, int beforeQty, int afterQty, Integer changedBy, LocalDateTime changedAt) {
        this.targetDate = targetDate;
        this.beforeQty = beforeQty;
        this.afterQty = afterQty;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    /** 이 변경으로 추가 주문 배정 보고서를 만들면 */
    public void link(Long reportId) {
        this.reportId = reportId;
    }

    public void close(LocalDateTime at) {
        if (this.closedAt == null) this.closedAt = at;
    }
}
