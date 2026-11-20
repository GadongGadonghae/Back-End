package com.gadong.sfaas.availability.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 재배정 표 한 줄. v0.3.10: line_id에 도장 보고서면 설비 ID(PT1·PT2)도 들어가요, remaining_plan 추가 */
@Entity @Table(name = "reassignment_detail") @IdClass(ReassignmentDetail.Key.class) @Getter @NoArgsConstructor
public class ReassignmentDetail {
    @Id @Column(name = "report_id") private Long reportId;
    @Id @Column(name = "line_id", length = 20) private String lineId;
    @Column(name = "spare_capacity") private Integer spareCapacity;
    @Column(name = "added_qty") private Integer addedQty;
    @Column(name = "remaining_plan") private Integer remainingPlan;

    public ReassignmentDetail(Long reportId, String lineId, int spareCapacity, int addedQty, int remainingPlan) {
        this.reportId = reportId;
        this.lineId = lineId;
        this.spareCapacity = spareCapacity;
        this.addedQty = addedQty;
        this.remainingPlan = remainingPlan;
    }

    @EqualsAndHashCode @NoArgsConstructor
    public static class Key implements Serializable {
        private Long reportId;
        private String lineId;
    }
}
