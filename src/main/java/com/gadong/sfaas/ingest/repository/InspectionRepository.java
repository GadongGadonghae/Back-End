package com.gadong.sfaas.ingest.repository;

import com.gadong.sfaas.ingest.entity.Inspection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {
}
