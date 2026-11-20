package com.gadong.sfaas.judge.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 설비 상태 이력(v0.3.1에서 status_log → equipment_status_history). status는 R/S/I/D/DC, ended_at이 null이면 현재 상태 */
@Entity @Table(name = "equipment_status_history") @Getter @NoArgsConstructor
public class StatusLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "log_id") private Long logId;
    @Column(name = "equipment_id") private String equipmentId;
    @Column(name = "status") private String status;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "ended_at") private LocalDateTime endedAt;
    /** S·I·D 구간이면 해당 비가동 번호, R·DC는 null (v0.3.2) */
    @Column(name = "downtime_id") private Long downtimeId;
}
