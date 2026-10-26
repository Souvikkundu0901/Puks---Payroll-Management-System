package com.octopyder.payroll.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "holidays")
public class Holiday {

    @Id
    @Column(name = "holiday_date")
    private LocalDate holidayDate;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Holiday() {
    }

    public Holiday(LocalDate holidayDate, String name) {
        this.holidayDate = holidayDate;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public void setHolidayDate(LocalDate holidayDate) {
        this.holidayDate = holidayDate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
