package com.gadong.sfaas.judge.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 비가동 구간(v0.3.1에서 downtime → downtime_event). ended_at이 null이면 진행 중. action_user_id(users FK)는 id 값만 보관 */
@Entity @Table(name = "downtime_event") @Getter @NoArgsConstructor
public class Downtime {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "downtime_id") private Long downtimeId;
    @Column(name = "equipment_id") private String equipmentId;
    /** DB가 CHAR(1)이라 VARCHAR가 아닌 CHAR로 매핑해요 */
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "status", length = 1) private String status;
    @Column(name = "code") private String code;
    @Column(name = "detail") private String detail;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "ended_at") private LocalDateTime endedAt;
    @Column(name = "action_memo") private String actionMemo;
    @Column(name = "action_user_id") private Integer actionUserId;
    @Column(name = "action_at") private LocalDateTime actionAt;
    /** 라인 단위 계획정지 묶음 번호 (v0.3.2). 같은 정지면 같은 번호 */
    @Column(name = "stop_group_id") private Long stopGroupId;

    /** U-09: 자동으로 붙은 사유·조치 내용을 사람이 고쳐요. null인 값은 그대로 둬요 */
    public void correct(String code, String memo, Integer actionUserId, LocalDateTime at) {
        if (code != null) this.code = code;
        if (memo != null) this.actionMemo = memo;
        if (actionUserId != null) this.actionUserId = actionUserId;
        if (memo != null || actionUserId != null) this.actionAt = at;
    }
}
