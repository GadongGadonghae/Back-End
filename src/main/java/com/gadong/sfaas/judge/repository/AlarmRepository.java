package com.gadong.sfaas.judge.repository;

import com.gadong.sfaas.judge.entity.Alarm;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {
}
