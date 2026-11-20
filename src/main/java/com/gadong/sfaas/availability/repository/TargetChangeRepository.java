package com.gadong.sfaas.availability.repository;

import com.gadong.sfaas.availability.entity.TargetChange;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TargetChangeRepository extends JpaRepository<TargetChange, Long> {
    /** 그날 아직 닫히지 않은(배정 대기) 변경 */
    List<TargetChange> findByTargetDateAndClosedAtIsNullOrderByChangeIdDesc(LocalDate targetDate);

    Optional<TargetChange> findFirstByReportIdOrderByChangeIdDesc(Long reportId);
}
