package com.gadong.sfaas.ingest.repository;

import com.gadong.sfaas.ingest.entity.PaintingData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaintingDataRepository extends JpaRepository<PaintingData, Long> {
}
