package com.gadong.sfaas.availability.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "production_target") @IdClass(ProductionTarget.Key.class) @Getter @NoArgsConstructor
public class ProductionTarget {
    @Id @Column(name = "target_date") private LocalDate targetDate;
    @Id @Column(name = "line_id") private String lineId;
    @Column(name = "target_qty") private Integer targetQty;
    @Column(name = "created_by") private Integer createdBy;
    @Column(name = "created_at") private LocalDateTime createdAt;

    /** G-03. createdBy·createdAt = 마지막으로 저장한 사람·시각 (11/20: 바꿀 때도 갱신) */
    public ProductionTarget(LocalDate targetDate, String lineId, int targetQty, LocalDateTime createdAt, Integer createdBy) {
        this.targetDate = targetDate;
        this.lineId = lineId;
        this.targetQty = targetQty;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public void change(int targetQty, LocalDateTime at, Integer by) {
        this.targetQty = targetQty;
        this.createdAt = at;
        this.createdBy = by;
    }

    @EqualsAndHashCode @NoArgsConstructor @AllArgsConstructor
    public static class Key implements Serializable {
        private LocalDate targetDate;
        private String lineId;
    }
}
