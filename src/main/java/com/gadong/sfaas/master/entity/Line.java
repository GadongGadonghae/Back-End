package com.gadong.sfaas.master.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "line") @Getter @NoArgsConstructor
public class Line {
    @Id @Column(name = "line_id") private String lineId;
    @Column(name = "line_name") private String lineName;
    @Column(name = "paint_equipment_id") private String paintEquipmentId;
    @Column(name = "capacity_per_hour") private Integer capacityPerHour;
}
