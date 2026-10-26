package com.octopyder.payroll.people;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, UUID> {

    boolean existsByPhotoKey(String photoKey);
}
