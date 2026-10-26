package com.octopyder.payroll.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OfficeLocationRepository extends JpaRepository<OfficeLocation, UUID> {

    List<OfficeLocation> findByActiveTrue();
}
