package com.octopyder.payroll.payroll;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PayrollLineRepository extends JpaRepository<PayrollLine, UUID> {
    List<PayrollLine> findByRecordIdOrderByDisplayOrderAsc(UUID recordId);
    void deleteByRecordId(UUID recordId);
}
