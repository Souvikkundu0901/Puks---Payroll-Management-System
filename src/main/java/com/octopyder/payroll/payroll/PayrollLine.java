package com.octopyder.payroll.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "payroll_lines")
public class PayrollLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayrollLineKind kind;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    public PayrollLine() {}

    public PayrollLine(UUID recordId, PayrollLineKind kind, String label, BigDecimal amount, int displayOrder) {
        this.recordId = recordId;
        this.kind = kind;
        this.label = label;
        this.amount = amount != null ? amount : BigDecimal.ZERO;
        this.displayOrder = displayOrder;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getRecordId() { return recordId; }
    public void setRecordId(UUID recordId) { this.recordId = recordId; }

    public PayrollLineKind getKind() { return kind; }
    public void setKind(PayrollLineKind kind) { this.kind = kind; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
}
