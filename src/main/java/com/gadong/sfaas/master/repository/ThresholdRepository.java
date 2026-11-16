package com.gadong.sfaas.master.repository;

import com.gadong.sfaas.master.entity.Threshold;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThresholdRepository extends JpaRepository<Threshold, Long> {
}
