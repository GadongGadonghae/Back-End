package com.gadong.sfaas.judge.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 알람: 관리값 이탈 → 발생(raised_at), 범위 복귀 → 해제(cleared_at). raw_values(JSONB)는 아직 매핑하지 않아요. */
@Entity @Table(name = "alarm") @Getter @NoArgsConstructor
public class Alarm {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "alarm_id") private Long alarmId;
    @Column(name = "equipment_id") private String equipmentId;
    @Column(name = "item_name") private String itemName;
    @Column(name = "measured_value") private BigDecimal measuredValue;
    @Column(name = "lower_limit") private BigDecimal lowerLimit;
    @Column(name = "upper_limit") private BigDecimal upperLimit;
    @Column(name = "level") private String level;
    @Column(name = "message") private String message;
    @Column(name = "raised_at") private LocalDateTime raisedAt;
    @Column(name = "cleared_at") private LocalDateTime clearedAt;
    @Column(name = "acked_by") private Integer ackedBy;
    @Column(name = "acked_at") private LocalDateTime ackedAt;

    /** 이미 확인된 알람은 처음 확인한 사람·시각을 유지해요 */
    public void ack(Integer userId, LocalDateTime at) {
        if (ackedAt != null) return;
        ackedBy = userId;
        ackedAt = at;
    }
}
