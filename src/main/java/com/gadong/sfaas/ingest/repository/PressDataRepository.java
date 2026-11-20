package com.gadong.sfaas.ingest.repository;

import com.gadong.sfaas.ingest.entity.PressData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PressDataRepository extends JpaRepository<PressData, Long> {
}
