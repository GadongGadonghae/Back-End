package com.gadong.sfaas.master.repository;

import com.gadong.sfaas.master.entity.Process;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessRepository extends JpaRepository<Process, String> {
}
