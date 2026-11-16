package com.gadong.sfaas.ingest.repository;

import com.gadong.sfaas.ingest.entity.CoilReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoilReceiptRepository extends JpaRepository<CoilReceipt, Long> {
}
