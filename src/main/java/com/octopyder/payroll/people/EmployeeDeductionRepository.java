package com.octopyder.payroll.people;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeDeductionRepository extends JpaRepository<EmployeeDeduction, UUID> {

    List<EmployeeDeduction> findByUserId(UUID userId);

    Optional<EmployeeDeduction> findByUserIdAndType(UUID userId, String type);

    void deleteByUserId(UUID userId);
}
