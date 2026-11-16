package com.gadong.sfaas.availability.repository;

import com.gadong.sfaas.availability.entity.ReassignmentDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReassignmentDetailRepository extends JpaRepository<ReassignmentDetail, ReassignmentDetail.Key> {
}
