package com.octopyder.payroll.people;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, UUID> {

    List<EmployeeDocument> findByUserIdOrderByUploadedAtDesc(UUID userId);

    Optional<EmployeeDocument> findByIdAndUserId(UUID id, UUID userId);

    Optional<EmployeeDocument> findByStorageKey(String storageKey);
}
