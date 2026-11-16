package com.gadong.sfaas.availability.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "reassignment_detail") @IdClass(ReassignmentDetail.Key.class) @Getter @NoArgsConstructor
public class ReassignmentDetail {
    @Id @Column(name = "report_id") private Long reportId;
    @Id @Column(name = "line_id") private String lineId;
    @Column(name = "spare_capacity") private Integer spareCapacity;
    @Column(name = "added_qty") private Integer addedQty;

    @EqualsAndHashCode @NoArgsConstructor
    public static class Key implements Serializable {
        private Long reportId;
        private String lineId;
    }
}
