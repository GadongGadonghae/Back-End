package com.gadong.sfaas.judge.repository;

import com.gadong.sfaas.judge.entity.Downtime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DowntimeRepository extends JpaRepository<Downtime, Long> {
}
