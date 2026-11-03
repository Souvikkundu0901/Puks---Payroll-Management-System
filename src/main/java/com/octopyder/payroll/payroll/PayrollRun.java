package com.octopyder.payroll.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "payroll_runs")
public class PayrollRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private LocalDate month;

    @Column(name = "department_id")
    private UUID departmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayrollRunStatus status = PayrollRunStatus.OPEN;

    @Column(name = "generated_by", nullable = false)
    private UUID generatedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb")
    private String params;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public PayrollRun() {}

    public PayrollRun(LocalDate month, UUID departmentId, PayrollRunStatus status, UUID generatedBy, String params, Instant createdAt) {
        this.month = month;
        this.departmentId = departmentId;
        this.status = status;
        this.generatedBy = generatedBy;
        this.params = params;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public LocalDate getMonth() { return month; }
    public void setMonth(LocalDate month) { this.month = month; }

    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID departmentId) { this.departmentId = departmentId; }

    public PayrollRunStatus getStatus() { return status; }
    public void setStatus(PayrollRunStatus status) { this.status = status; }

    public UUID getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(UUID generatedBy) { this.generatedBy = generatedBy; }

    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
