package com.gadong.sfaas.availability.repository;

import com.gadong.sfaas.availability.entity.ProductionTarget;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionTargetRepository extends JpaRepository<ProductionTarget, ProductionTarget.Key> {
    List<ProductionTarget> findByTargetDateBetweenOrderByTargetDateAscLineIdAsc(LocalDate from, LocalDate to);

    List<ProductionTarget> findByTargetDateBetweenAndLineIdOrderByTargetDateAsc(LocalDate from, LocalDate to, String lineId);
}
