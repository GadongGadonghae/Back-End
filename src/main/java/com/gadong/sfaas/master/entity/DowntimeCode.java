package com.gadong.sfaas.master.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "downtime_code") @Getter @NoArgsConstructor
public class DowntimeCode {
    @Id @Column(name = "code") private String code;
    @Column(name = "code_name") private String codeName;
    @Column(name = "is_planned") private Boolean planned;
    @Column(name = "description") private String description;
}
