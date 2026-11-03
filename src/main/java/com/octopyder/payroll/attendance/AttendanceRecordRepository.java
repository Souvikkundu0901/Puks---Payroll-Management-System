package com.octopyder.payroll.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID>, JpaSpecificationExecutor<AttendanceRecord> {

    Optional<AttendanceRecord> findByUserIdAndWorkDate(UUID userId, LocalDate workDate);

    List<AttendanceRecord> findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(UUID userId, LocalDate from, LocalDate to);

    List<AttendanceRecord> findByUserIdInAndWorkDateBetweenOrderByWorkDateAsc(java.util.Collection<UUID> userIds, LocalDate from, LocalDate to);

    List<AttendanceRecord> findByWorkDateBeforeAndCheckOutAtIsNullAndMissedCheckoutIsFalse(LocalDate workDate);

    List<AttendanceRecord> findByWorkDateAndCheckOutAtIsNullAndMissedCheckoutIsFalse(LocalDate workDate);

    long countByApprovalStatus(AttendanceApprovalStatus status);

    @Query("SELECT r FROM AttendanceRecord r WHERE r.approvalStatus = 'PENDING' AND r.userId IN :userIds ORDER BY r.workDate DESC, r.checkInAt DESC")
    List<AttendanceRecord> findPendingByUserIds(@Param("userIds") List<UUID> userIds);

    @Query("SELECT r FROM AttendanceRecord r WHERE r.approvalStatus = 'PENDING' ORDER BY r.workDate DESC, r.checkInAt DESC")
    List<AttendanceRecord> findAllPending();

    long countByWorkDateAndApprovalStatus(LocalDate workDate, AttendanceApprovalStatus status);

    long countByUserIdInAndWorkDateAndApprovalStatus(java.util.Collection<UUID> userIds, LocalDate workDate, AttendanceApprovalStatus status);

    long countByUserIdInAndApprovalStatus(java.util.Collection<UUID> userIds, AttendanceApprovalStatus status);

    long countByUserIdAndWorkDateBetweenAndApprovalStatus(UUID userId, LocalDate from, LocalDate to, AttendanceApprovalStatus status);
}
