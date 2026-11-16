package com.gadong.sfaas.master.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "process") @Getter @NoArgsConstructor
public class Process {
    @Id @Column(name = "process_id") private String processId;
    @Column(name = "process_name") private String processName;
    @Column(name = "seq") private Integer seq;
}
