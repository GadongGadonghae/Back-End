package com.gadong.sfaas.report.flow;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByKindAndTargetKey(String kind, String targetKey);

    Optional<Report> findFirstByReplanReportIdOrderByReportIdDesc(Long replanReportId);
}
