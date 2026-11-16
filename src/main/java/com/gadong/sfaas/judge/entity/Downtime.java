package com.gadong.sfaas.judge.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 비가동 구간. ended_at이 null이면 진행 중. action_user_id(users FK)는 id 값만 보관 */
@Entity @Table(name = "downtime") @Getter @NoArgsConstructor
public class Downtime {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "downtime_id") private Long downtimeId;
    @Column(name = "equipment_id") private String equipmentId;
    @Column(name = "status") private String status;
    @Column(name = "code") private String code;
    @Column(name = "detail") private String detail;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "ended_at") private LocalDateTime endedAt;
    @Column(name = "action_memo") private String actionMemo;
    @Column(name = "action_user_id") private Long actionUserId;
    @Column(name = "action_at") private LocalDateTime actionAt;
}
