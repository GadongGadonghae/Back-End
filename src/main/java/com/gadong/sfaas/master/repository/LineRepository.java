package com.gadong.sfaas.master.repository;

import com.gadong.sfaas.master.entity.Line;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LineRepository extends JpaRepository<Line, String> {
    boolean existsByLineId(String lineId);
}
