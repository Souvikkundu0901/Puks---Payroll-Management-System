package com.octopyder.payroll.payroll;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollRecordRepository extends JpaRepository<PayrollRecord, UUID>, JpaSpecificationExecutor<PayrollRecord> {

    Optional<PayrollRecord> findByUserIdAndMonth(UUID userId, LocalDate month);

    List<PayrollRecord> findByRunId(UUID runId);

    List<PayrollRecord> findByMonth(LocalDate month);

    List<PayrollRecord> findByMonthAndUserIdIn(LocalDate month, Collection<UUID> userIds);

    List<PayrollRecord> findByUserIdOrderByMonthDesc(UUID userId);

    Optional<PayrollRecord> findFirstByUserIdOrderByMonthDesc(UUID userId);

    @Query("SELECT r FROM PayrollRecord r WHERE r.userId = :userId AND r.month >= :start AND r.month <= :end ORDER BY r.month DESC")
    List<PayrollRecord> findByUserIdAndYear(@Param("userId") UUID userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
        SELECT r FROM PayrollRecord r
        WHERE (:month IS NULL OR r.month = :month)
          AND (:status IS NULL OR r.status = :status)
          AND (:userIds IS NULL OR r.userId IN :userIds)
          AND (
            :band IS NULL
            OR (:band = 'GTE70' AND r.completionPct >= 70.00)
            OR (:band = 'B50_69' AND r.completionPct >= 50.00 AND r.completionPct < 70.00)
            OR (:band = 'LT50' AND r.completionPct < 50.00)
          )
        ORDER BY r.createdAt DESC
        """)
    Page<PayrollRecord> findRecords(
        @Param("month") LocalDate month,
        @Param("status") PayrollRecordStatus status,
        @Param("userIds") Collection<UUID> userIds,
        @Param("band") String band,
        Pageable pageable
    );

    @Query("SELECT COUNT(r) > 0 FROM PayrollRecord r WHERE r.userId = :userId AND r.month = :month AND (r.status IN ('APPROVED', 'RELEASED') OR r.paidAmount > 0)")
    boolean existsApprovedOrPaid(@Param("userId") UUID userId, @Param("month") LocalDate month);

    @Query("SELECT COUNT(r) > 0 FROM PayrollRecord r WHERE r.userId = :userId AND (r.status IN ('APPROVED', 'RELEASED') OR r.paidAmount > 0)")
    boolean existsApprovedOrPaidForUser(@Param("userId") UUID userId);

    long countByUserIdInAndStatus(Collection<UUID> userIds, PayrollRecordStatus status);
}
