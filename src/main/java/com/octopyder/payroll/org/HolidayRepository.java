package com.octopyder.payroll.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, LocalDate> {

    List<Holiday> findByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate start, LocalDate end);
}
