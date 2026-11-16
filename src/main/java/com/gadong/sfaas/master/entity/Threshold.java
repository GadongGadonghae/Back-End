package com.gadong.sfaas.master.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 관리값 기준. updated_by(users FK)는 auth 패키지 소유라 id 값만 들고 있어요. */
@Entity @Table(name = "threshold") @Getter @NoArgsConstructor
public class Threshold {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "threshold_id") private Long thresholdId;
    @Column(name = "process_id") private String processId;
    @Column(name = "item_name") private String itemName;
    @Column(name = "item_label") private String itemLabel;
    @Column(name = "unit") private String unit;
    @Column(name = "lower_limit") private Double lowerLimit;
    @Column(name = "upper_limit") private Double upperLimit;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @Column(name = "updated_by") private Long updatedBy;
}
