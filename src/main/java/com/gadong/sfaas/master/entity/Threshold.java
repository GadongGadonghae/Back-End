package com.gadong.sfaas.master.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 관리값 기준 (v0.3: 설비별). updated_by(users FK)는 auth 패키지 소유라 id 값만 들고 있어요. */
@Entity @Table(name = "threshold") @Getter @NoArgsConstructor
public class Threshold {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "threshold_id") private Integer thresholdId;
    @Column(name = "equipment_id") private String equipmentId;
    @Column(name = "item_name") private String itemName;
    @Column(name = "item_label") private String itemLabel;
    @Column(name = "unit") private String unit;
    @Column(name = "lower_limit") private BigDecimal lowerLimit;
    @Column(name = "upper_limit") private BigDecimal upperLimit;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @Column(name = "updated_by") private Integer updatedBy;

    /** M-11. 수정한 사람(updated_by)은 JWT 연동 후 채워요 */
    public void update(BigDecimal lowerLimit, BigDecimal upperLimit, LocalDateTime at) {
        this.lowerLimit = lowerLimit;
        this.upperLimit = upperLimit;
        this.updatedAt = at;
    }
}
