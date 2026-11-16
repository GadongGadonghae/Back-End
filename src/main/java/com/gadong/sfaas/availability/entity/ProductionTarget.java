package com.gadong.sfaas.availability.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "production_target") @IdClass(ProductionTarget.Key.class) @Getter @NoArgsConstructor
public class ProductionTarget {
    @Id @Column(name = "target_date") private LocalDate targetDate;
    @Id @Column(name = "line_id") private String lineId;
    @Column(name = "target_qty") private Integer targetQty;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "created_at") private LocalDateTime createdAt;

    @EqualsAndHashCode @NoArgsConstructor
    public static class Key implements Serializable {
        private LocalDate targetDate;
        private String lineId;
    }
}
