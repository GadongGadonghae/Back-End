package com.gadong.sfaas.judge.repository;

import com.gadong.sfaas.judge.entity.StatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusLogRepository extends JpaRepository<StatusLog, Long> {
}
