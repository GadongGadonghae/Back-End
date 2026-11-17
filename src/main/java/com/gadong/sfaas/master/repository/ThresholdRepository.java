package com.gadong.sfaas.master.repository;

import com.gadong.sfaas.master.entity.Threshold;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThresholdRepository extends JpaRepository<Threshold, Integer> {
    List<Threshold> findByEquipmentId(String equipmentId);
}
