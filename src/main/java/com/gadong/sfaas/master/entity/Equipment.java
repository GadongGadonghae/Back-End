package com.gadong.sfaas.master.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "equipment") @Getter @NoArgsConstructor
public class Equipment {
    @Id @Column(name = "equipment_id") private String equipmentId;
    @Column(name = "equipment_name") private String equipmentName;
    @Column(name = "process_id") private String processId;
    /** 공용 설비(도장·검사)는 null */
    @Column(name = "line_id") private String lineId;
    @Column(name = "seq_no") private Short seqNo;
    @Column(name = "is_active") private Boolean active;
}
