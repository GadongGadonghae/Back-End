package com.gadong.sfaas.ingest.repository;

import com.gadong.sfaas.ingest.entity.WeldingData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeldingDataRepository extends JpaRepository<WeldingData, Long> {
}
