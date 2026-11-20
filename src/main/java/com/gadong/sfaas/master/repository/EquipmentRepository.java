package com.gadong.sfaas.master.repository;

import com.gadong.sfaas.master.entity.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRepository extends JpaRepository<Equipment, String> {
}
