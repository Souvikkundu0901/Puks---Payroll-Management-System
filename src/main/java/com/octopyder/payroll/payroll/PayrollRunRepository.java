package com.octopyder.payroll.payroll;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {
    Optional<PayrollRun> findFirstByMonthAndDepartmentIdIsNullOrderByCreatedAtDesc(LocalDate month);
    Optional<PayrollRun> findFirstByMonthAndDepartmentIdOrderByCreatedAtDesc(LocalDate month, UUID departmentId);
    List<PayrollRun> findByMonthOrderByCreatedAtDesc(LocalDate month);
    Optional<PayrollRun> findFirstByOrderByCreatedAtDesc();
}
