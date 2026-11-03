package com.octopyder.payroll.payroll;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollRetentionRepository extends JpaRepository<PayrollRetention, UUID> {

    List<PayrollRetention> findByUserIdAndStatus(UUID userId, RetentionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PayrollRetention r WHERE r.userId = :userId AND r.status = :status")
    List<PayrollRetention> findByUserIdAndStatusForUpdate(@Param("userId") UUID userId, @Param("status") RetentionStatus status);

    List<PayrollRetention> findByStatusOrderByCreatedAtDesc(RetentionStatus status);

    List<PayrollRetention> findByUserIdInAndStatusOrderByCreatedAtDesc(Collection<UUID> userIds, RetentionStatus status);

    List<PayrollRetention> findByRecordId(UUID recordId);

    List<PayrollRetention> findByReleasedInRecordId(UUID recordId);
}
