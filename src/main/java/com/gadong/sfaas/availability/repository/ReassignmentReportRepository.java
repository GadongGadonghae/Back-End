package com.gadong.sfaas.availability.repository;

import com.gadong.sfaas.availability.entity.ReassignmentReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReassignmentReportRepository extends JpaRepository<ReassignmentReport, Long> {
}
