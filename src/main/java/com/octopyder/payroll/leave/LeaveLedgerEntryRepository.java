package com.octopyder.payroll.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaveLedgerEntryRepository extends JpaRepository<LeaveLedgerEntry, UUID> {

    List<LeaveLedgerEntry> findByUserIdOrderByEffectiveOnDescCreatedAtDesc(UUID userId);

    @Query("SELECT COALESCE(SUM(e.days), 0) FROM LeaveLedgerEntry e WHERE e.userId = :userId AND e.effectiveOn <= :asOfDate")
    BigDecimal getBalanceAsOf(@Param("userId") UUID userId, @Param("asOfDate") LocalDate asOfDate);

    @Query("SELECT COALESCE(SUM(e.days), 0) FROM LeaveLedgerEntry e WHERE e.userId = :userId AND e.entryType = 'CREDIT' AND e.leaveYear = :leaveYear AND e.effectiveOn <= :asOfDate")
    BigDecimal getEarnedInYear(@Param("userId") UUID userId, @Param("leaveYear") int leaveYear, @Param("asOfDate") LocalDate asOfDate);

    @Query("SELECT COALESCE(SUM(ABS(e.days)), 0) FROM LeaveLedgerEntry e WHERE e.userId = :userId AND e.entryType = 'DEBIT' AND e.leaveYear = :leaveYear AND e.effectiveOn <= :asOfDate")
    BigDecimal getUsedInYear(@Param("userId") UUID userId, @Param("leaveYear") int leaveYear, @Param("asOfDate") LocalDate asOfDate);

    boolean existsByUserIdAndEntryTypeAndLeaveYear(UUID userId, LeaveLedgerEntryType entryType, int leaveYear);

    boolean existsByUserIdAndEntryTypeAndPeriodMonth(UUID userId, LeaveLedgerEntryType entryType, LocalDate periodMonth);

    Optional<LeaveLedgerEntry> findByUserIdAndEntryTypeAndLeaveYear(UUID userId, LeaveLedgerEntryType entryType, int leaveYear);
}
