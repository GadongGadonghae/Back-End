package com.gadong.sfaas.master.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "process") @Getter @NoArgsConstructor
public class Process {
    @Id @Column(name = "process_id") private String processId;
    @Column(name = "process_name") private String processName;
    @Column(name = "seq") private Short seq;
    /** 제품 1개 처리 시간(초). 라인 생산 능력은 가장 느린 공정 기준으로 계산해요 (v0.3에서 line.capacity_per_hour 대체) */
    @Column(name = "cycle_time_sec") private BigDecimal cycleTimeSec;
}
