package com.gadong.sfaas.master.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "line") @Getter @NoArgsConstructor
public class Line {
    @Id @Column(name = "line_id") private String lineId;
    @Column(name = "line_name") private String lineName;
    @Column(name = "default_paint_id") private String defaultPaintId;
    @Column(name = "current_paint_id") private String currentPaintId;

    /** M-09 도장 경로 전환 */
    public void changePaint(String paintId) {
        this.currentPaintId = paintId;
    }
}
