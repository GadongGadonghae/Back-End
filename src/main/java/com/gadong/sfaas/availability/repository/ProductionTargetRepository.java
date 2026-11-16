package com.gadong.sfaas.availability.repository;

import com.gadong.sfaas.availability.entity.ProductionTarget;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionTargetRepository extends JpaRepository<ProductionTarget, ProductionTarget.Key> {
}
