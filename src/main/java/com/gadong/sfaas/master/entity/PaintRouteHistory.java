package com.gadong.sfaas.master.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 도장 경로 전환 이력 (M-09). changed_by(users FK)는 auth 패키지 소유라 id 값만 보관 */
@Entity @Table(name = "paint_route_history") @Getter @NoArgsConstructor
public class PaintRouteHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "history_id") private Long historyId;
    @Column(name = "line_id") private String lineId;
    @Column(name = "from_paint_id") private String fromPaintId;
    @Column(name = "to_paint_id") private String toPaintId;
    @Column(name = "reason") private String reason;
    @Column(name = "changed_by") private Integer changedBy;
    @Column(name = "changed_at") private LocalDateTime changedAt;

    public PaintRouteHistory(String lineId, String fromPaintId, String toPaintId, String reason, LocalDateTime changedAt) {
        this.lineId = lineId;
        this.fromPaintId = fromPaintId;
        this.toPaintId = toPaintId;
        this.reason = reason;
        this.changedAt = changedAt;
    }
}
