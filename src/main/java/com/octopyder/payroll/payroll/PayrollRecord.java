package com.octopyder.payroll.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "payroll_records",
    uniqueConstraints = @UniqueConstraint(name = "uq_payroll_records_user_month", columnNames = {"user_id", "month"})
)
public class PayrollRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private LocalDate month;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal gross = BigDecimal.ZERO;

    @Column(name = "tasks_total", nullable = false)
    private int tasksTotal;

    @Column(name = "tasks_approved", nullable = false)
    private int tasksApproved;

    @Column(name = "completion_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal completionPct = BigDecimal.ZERO;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    @Column(name = "retention_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal retentionAmount = BigDecimal.ZERO;

    @Column(name = "absent_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal absentDays = BigDecimal.ZERO;

    @Column(name = "absent_deduction", nullable = false, precision = 12, scale = 2)
    private BigDecimal absentDeduction = BigDecimal.ZERO;

    @Column(name = "unpaid_leave_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal unpaidLeaveDays = BigDecimal.ZERO;

    @Column(name = "unpaid_leave_deduction", nullable = false, precision = 12, scale = 2)
    private BigDecimal unpaidLeaveDeduction = BigDecimal.ZERO;

    @Column(name = "unpaid_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal unpaidDays = BigDecimal.ZERO;

    @Column(name = "unpaid_deduction", nullable = false, precision = 12, scale = 2)
    private BigDecimal unpaidDeduction = BigDecimal.ZERO;

    @Column(name = "tds_deduction", nullable = false, precision = 12, scale = 2)
    private BigDecimal tdsDeduction = BigDecimal.ZERO;

    @Column(name = "pf_deduction", nullable = false, precision = 12, scale = 2)
    private BigDecimal pfDeduction = BigDecimal.ZERO;

    @Column(name = "pt_deduction", nullable = false, precision = 12, scale = 2)
    private BigDecimal ptDeduction = BigDecimal.ZERO;

    @Column(name = "statutory_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal statutoryTotal = BigDecimal.ZERO;

    @Column(name = "retention_released_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal retentionReleasedAmount = BigDecimal.ZERO;

    @Column(name = "net_payable", nullable = false, precision = 12, scale = 2)
    private BigDecimal netPayable = BigDecimal.ZERO;

    @Column(name = "net_clamped", nullable = false)
    private boolean netClamped = false;

    @Column(name = "present_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal presentDays = BigDecimal.ZERO;

    @Column(name = "working_days", nullable = false)
    private int workingDays;

    @Column(name = "default_release_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal defaultReleaseAmount = BigDecimal.ZERO;

    @Column(name = "task_linked_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taskLinkedAmount = BigDecimal.ZERO;

    @Column(name = "task_unlocked_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taskUnlockedAmount = BigDecimal.ZERO;

    @Column(name = "released_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal releasedPct = BigDecimal.ZERO;

    @Column(name = "tranche1_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal tranche1Amount = BigDecimal.ZERO;

    @Column(name = "tranche2_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal tranche2Amount = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "tranche1_paid_at")
    private Instant tranche1PaidAt;

    @Column(name = "tranche1_payment_ref", length = 100)
    private String tranche1PaymentRef;

    @Column(name = "tranche1_payment_method", length = 50)
    private String tranche1PaymentMethod;

    @Column(name = "tranche1_payment_date")
    private LocalDate tranche1PaymentDate;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "payment_ref", length = 100)
    private String paymentRef;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayrollRecordStatus status = PayrollRecordStatus.GENERATED;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "reject_note")
    private String rejectNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", columnDefinition = "jsonb", nullable = false)
    private String snapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public PayrollRecord() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public LocalDate getMonth() { return month; }
    public void setMonth(LocalDate month) { this.month = month; }

    public BigDecimal getGross() { return gross; }
    public void setGross(BigDecimal gross) { this.gross = gross; }

    public int getTasksTotal() { return tasksTotal; }
    public void setTasksTotal(int tasksTotal) { this.tasksTotal = tasksTotal; }

    public int getTasksApproved() { return tasksApproved; }
    public void setTasksApproved(int tasksApproved) { this.tasksApproved = tasksApproved; }

    public BigDecimal getCompletionPct() { return completionPct; }
    public void setCompletionPct(BigDecimal completionPct) { this.completionPct = completionPct; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public BigDecimal getRetentionAmount() { return retentionAmount; }
    public void setRetentionAmount(BigDecimal retentionAmount) { this.retentionAmount = retentionAmount; }

    public BigDecimal getAbsentDays() { return absentDays; }
    public void setAbsentDays(BigDecimal absentDays) { this.absentDays = absentDays; }

    public BigDecimal getAbsentDeduction() { return absentDeduction; }
    public void setAbsentDeduction(BigDecimal absentDeduction) { this.absentDeduction = absentDeduction; }

    public BigDecimal getUnpaidLeaveDays() { return unpaidLeaveDays; }
    public void setUnpaidLeaveDays(BigDecimal unpaidLeaveDays) { this.unpaidLeaveDays = unpaidLeaveDays; }

    public BigDecimal getUnpaidLeaveDeduction() { return unpaidLeaveDeduction; }
    public void setUnpaidLeaveDeduction(BigDecimal unpaidLeaveDeduction) { this.unpaidLeaveDeduction = unpaidLeaveDeduction; }

    public BigDecimal getUnpaidDays() { return unpaidDays; }
    public void setUnpaidDays(BigDecimal unpaidDays) { this.unpaidDays = unpaidDays; }

    public BigDecimal getUnpaidDeduction() { return unpaidDeduction; }
    public void setUnpaidDeduction(BigDecimal unpaidDeduction) { this.unpaidDeduction = unpaidDeduction; }

    public BigDecimal getTdsDeduction() { return tdsDeduction; }
    public void setTdsDeduction(BigDecimal tdsDeduction) { this.tdsDeduction = tdsDeduction; }

    public BigDecimal getPfDeduction() { return pfDeduction; }
    public void setPfDeduction(BigDecimal pfDeduction) { this.pfDeduction = pfDeduction; }

    public BigDecimal getPtDeduction() { return ptDeduction; }
    public void setPtDeduction(BigDecimal ptDeduction) { this.ptDeduction = ptDeduction; }

    public BigDecimal getStatutoryTotal() { return statutoryTotal; }
    public void setStatutoryTotal(BigDecimal statutoryTotal) { this.statutoryTotal = statutoryTotal; }

    public BigDecimal getRetentionReleasedAmount() { return retentionReleasedAmount; }
    public void setRetentionReleasedAmount(BigDecimal retentionReleasedAmount) { this.retentionReleasedAmount = retentionReleasedAmount; }

    public BigDecimal getNetPayable() { return netPayable; }
    public void setNetPayable(BigDecimal netPayable) { this.netPayable = netPayable; }

    public boolean isNetClamped() { return netClamped; }
    public void setNetClamped(boolean netClamped) { this.netClamped = netClamped; }

    public BigDecimal getPresentDays() { return presentDays; }
    public void setPresentDays(BigDecimal presentDays) { this.presentDays = presentDays; }

    public int getWorkingDays() { return workingDays; }
    public void setWorkingDays(int workingDays) { this.workingDays = workingDays; }

    public BigDecimal getDefaultReleaseAmount() { return defaultReleaseAmount; }
    public void setDefaultReleaseAmount(BigDecimal defaultReleaseAmount) { this.defaultReleaseAmount = defaultReleaseAmount; }

    public BigDecimal getTaskLinkedAmount() { return taskLinkedAmount; }
    public void setTaskLinkedAmount(BigDecimal taskLinkedAmount) { this.taskLinkedAmount = taskLinkedAmount; }

    public BigDecimal getTaskUnlockedAmount() { return taskUnlockedAmount; }
    public void setTaskUnlockedAmount(BigDecimal taskUnlockedAmount) { this.taskUnlockedAmount = taskUnlockedAmount; }

    public BigDecimal getReleasedPct() { return releasedPct; }
    public void setReleasedPct(BigDecimal releasedPct) { this.releasedPct = releasedPct; }

    public BigDecimal getTranche1Amount() { return tranche1Amount; }
    public void setTranche1Amount(BigDecimal tranche1Amount) { this.tranche1Amount = tranche1Amount; }

    public BigDecimal getTranche2Amount() { return tranche2Amount; }
    public void setTranche2Amount(BigDecimal tranche2Amount) { this.tranche2Amount = tranche2Amount; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public Instant getTranche1PaidAt() { return tranche1PaidAt; }
    public void setTranche1PaidAt(Instant tranche1PaidAt) { this.tranche1PaidAt = tranche1PaidAt; }

    public String getTranche1PaymentRef() { return tranche1PaymentRef; }
    public void setTranche1PaymentRef(String tranche1PaymentRef) { this.tranche1PaymentRef = tranche1PaymentRef; }

    public String getTranche1PaymentMethod() { return tranche1PaymentMethod; }
    public void setTranche1PaymentMethod(String tranche1PaymentMethod) { this.tranche1PaymentMethod = tranche1PaymentMethod; }

    public LocalDate getTranche1PaymentDate() { return tranche1PaymentDate; }
    public void setTranche1PaymentDate(LocalDate tranche1PaymentDate) { this.tranche1PaymentDate = tranche1PaymentDate; }

    public LocalDate getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDate paymentDate) { this.paymentDate = paymentDate; }

    public String getPaymentRef() { return paymentRef; }
    public void setPaymentRef(String paymentRef) { this.paymentRef = paymentRef; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public PayrollRecordStatus getStatus() { return status; }
    public void setStatus(PayrollRecordStatus status) { this.status = status; }

    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID approvedBy) { this.approvedBy = approvedBy; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public UUID getReleasedBy() { return releasedBy; }
    public void setReleasedBy(UUID releasedBy) { this.releasedBy = releasedBy; }

    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }

    public String getRejectNote() { return rejectNote; }
    public void setRejectNote(String rejectNote) { this.rejectNote = rejectNote; }

    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
