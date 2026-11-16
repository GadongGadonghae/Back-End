package com.gadong.sfaas.ingest.repository;

import com.gadong.sfaas.ingest.entity.SlittingData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlittingDataRepository extends JpaRepository<SlittingData, Long> {
}
