package com.gadong.sfaas.master.repository;

import com.gadong.sfaas.master.entity.DowntimeCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DowntimeCodeRepository extends JpaRepository<DowntimeCode, String> {
}
