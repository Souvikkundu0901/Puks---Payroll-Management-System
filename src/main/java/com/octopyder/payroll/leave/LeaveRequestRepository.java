package com.octopyder.payroll.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID>, JpaSpecificationExecutor<LeaveRequest> {

    List<LeaveRequest> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("""
        SELECT r FROM LeaveRequest r
        WHERE r.userId = :userId
          AND r.status IN :statuses
          AND r.startDate <= :endDate
          AND r.endDate >= :startDate
    """)
    List<LeaveRequest> findOverlapping(
        @Param("userId") UUID userId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("statuses") Collection<LeaveStatus> statuses
    );

    @Query("""
        SELECT r FROM LeaveRequest r
        WHERE r.userId = :userId
          AND r.status = 'APPROVED'
          AND r.startDate <= :endDate
          AND r.endDate >= :startDate
    """)
    List<LeaveRequest> findApprovedInDateRange(
        @Param("userId") UUID userId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    @Query("""
        SELECT COUNT(r) > 0 FROM LeaveRequest r
        WHERE r.userId = :userId
          AND r.status = 'APPROVED'
          AND r.startDate <= :date
          AND r.endDate >= :date
    """)
    boolean hasApprovedLeaveOnDate(@Param("userId") UUID userId, @Param("date") LocalDate date);

    @Query("""
        SELECT r FROM LeaveRequest r
        WHERE r.status = 'PENDING'
          AND r.startDate <= :endDate
          AND r.endDate >= :startDate
    """)
    List<LeaveRequest> findPendingOverlapping(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    long countByStatus(LeaveStatus status);

    long countByUserIdInAndStatus(Collection<UUID> userIds, LeaveStatus status);

    @Query("SELECT r.leaveType, COUNT(r) FROM LeaveRequest r WHERE r.status = 'APPROVED' AND r.startDate >= :from AND r.startDate <= :to GROUP BY r.leaveType")
    List<Object[]> countApprovedByTypeInDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT r FROM LeaveRequest r WHERE r.status = 'APPROVED' AND r.startDate <= :to AND r.endDate >= :from")
    List<LeaveRequest> findAllApprovedInDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT r FROM LeaveRequest r WHERE r.userId IN :userIds AND r.status = 'APPROVED' AND r.startDate <= :to AND r.endDate >= :from")
    List<LeaveRequest> findTeamApprovedInDateRange(@Param("userIds") Collection<UUID> userIds, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
