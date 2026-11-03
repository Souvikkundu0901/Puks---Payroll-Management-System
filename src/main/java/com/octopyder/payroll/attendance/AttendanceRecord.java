package com.octopyder.payroll.attendance;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "attendance_records",
    uniqueConstraints = @UniqueConstraint(name = "uq_attendance_user_date", columnNames = {"user_id", "work_date"})
)
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "check_in_at", nullable = false)
    private Instant checkInAt;

    @Column(name = "check_out_at")
    private Instant checkOutAt;

    @Column(name = "worked_minutes", nullable = false)
    private int workedMinutes = 0;

    @Column(name = "overtime_minutes", nullable = false)
    private int overtimeMinutes = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false, length = 16)
    private AttendanceWorkMode workMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 16)
    private AttendanceDayType dayType = AttendanceDayType.PRESENT;

    @Column(name = "is_late", nullable = false)
    private boolean isLate = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 16)
    private AttendanceApprovalStatus approvalStatus = AttendanceApprovalStatus.PENDING;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_note")
    private String rejectionNote;

    @Column(name = "missed_checkout", nullable = false)
    private boolean missedCheckout = false;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "accuracy_m", precision = 8, scale = 2)
    private BigDecimal accuracyM;

    @Column(name = "low_accuracy", nullable = false)
    private boolean lowAccuracy = false;

    @Column(name = "device_platform", length = 64)
    private String devicePlatform;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "office_location_id")
    private UUID officeLocationId;

    @Column(name = "distance_m", precision = 10, scale = 2)
    private BigDecimal distanceM;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AttendanceRecord() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }

    public Instant getCheckInAt() { return checkInAt; }
    public void setCheckInAt(Instant checkInAt) { this.checkInAt = checkInAt; }

    public Instant getCheckOutAt() { return checkOutAt; }
    public void setCheckOutAt(Instant checkOutAt) { this.checkOutAt = checkOutAt; }

    public int getWorkedMinutes() { return workedMinutes; }
    public void setWorkedMinutes(int workedMinutes) { this.workedMinutes = workedMinutes; }

    public int getOvertimeMinutes() { return overtimeMinutes; }
    public void setOvertimeMinutes(int overtimeMinutes) { this.overtimeMinutes = overtimeMinutes; }

    public AttendanceWorkMode getWorkMode() { return workMode; }
    public void setWorkMode(AttendanceWorkMode workMode) { this.workMode = workMode; }

    public AttendanceDayType getDayType() { return dayType; }
    public void setDayType(AttendanceDayType dayType) { this.dayType = dayType; }

    public boolean isLate() { return isLate; }
    public void setLate(boolean late) { isLate = late; }

    public AttendanceApprovalStatus getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(AttendanceApprovalStatus approvalStatus) { this.approvalStatus = approvalStatus; }

    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID approvedBy) { this.approvedBy = approvedBy; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public String getRejectionNote() { return rejectionNote; }
    public void setRejectionNote(String rejectionNote) { this.rejectionNote = rejectionNote; }

    public boolean isMissedCheckout() { return missedCheckout; }
    public void setMissedCheckout(boolean missedCheckout) { this.missedCheckout = missedCheckout; }

    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }

    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }

    public BigDecimal getAccuracyM() { return accuracyM; }
    public void setAccuracyM(BigDecimal accuracyM) { this.accuracyM = accuracyM; }

    public boolean isLowAccuracy() { return lowAccuracy; }
    public void setLowAccuracy(boolean lowAccuracy) { this.lowAccuracy = lowAccuracy; }

    public String getDevicePlatform() { return devicePlatform; }
    public void setDevicePlatform(String devicePlatform) { this.devicePlatform = devicePlatform; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public UUID getOfficeLocationId() { return officeLocationId; }
    public void setOfficeLocationId(UUID officeLocationId) { this.officeLocationId = officeLocationId; }

    public BigDecimal getDistanceM() { return distanceM; }
    public void setDistanceM(BigDecimal distanceM) { this.distanceM = distanceM; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
