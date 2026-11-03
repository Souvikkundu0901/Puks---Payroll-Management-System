package com.octopyder.payroll.people;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "employee_profiles")
public class EmployeeProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "first_name", nullable = false, length = 64)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 64)
    private String lastName;

    @Column(length = 16)
    private String gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(length = 32)
    private String phone;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "blood_group", length = 8)
    private String bloodGroup;

    @Column(name = "emergency_contact", length = 64)
    private String emergencyContact;

    @Column(name = "marital_status", length = 16)
    private String maritalStatus;

    @Column(name = "photo_key")
    private String photoKey;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(nullable = false, length = 64)
    private String designation;

    @Column(name = "employment_type", nullable = false, length = 32)
    private String employmentType = "FULL_TIME";

    @Column(name = "joining_date", nullable = false)
    private LocalDate joiningDate = LocalDate.now();

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "base_salary", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseSalary = BigDecimal.ZERO;

    @Column(name = "experience_years", precision = 4, scale = 1)
    private BigDecimal experienceYears = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "skills", columnDefinition = "text[]")
    private String[] skills = new String[0];

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public EmployeeProfile() {
    }

    public EmployeeProfile(UUID userId, String firstName, String lastName, String designation, BigDecimal baseSalary) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.designation = designation;
        this.baseSalary = baseSalary != null ? baseSalary : BigDecimal.ZERO;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getBloodGroup() {
        return bloodGroup;
    }

    public void setBloodGroup(String bloodGroup) {
        this.bloodGroup = bloodGroup;
    }

    public String getEmergencyContact() {
        return emergencyContact;
    }

    public void setEmergencyContact(String emergencyContact) {
        this.emergencyContact = emergencyContact;
    }

    public String getMaritalStatus() {
        return maritalStatus;
    }

    public void setMaritalStatus(String maritalStatus) {
        this.maritalStatus = maritalStatus;
    }

    public String getPhotoKey() {
        return photoKey;
    }

    public void setPhotoKey(String photoKey) {
        this.photoKey = photoKey;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }

    public String getDesignation() {
        return designation;
    }

    public void setDesignation(String designation) {
        this.designation = designation;
    }

    public String getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(String employmentType) {
        this.employmentType = employmentType;
    }

    public LocalDate getJoiningDate() {
        return joiningDate;
    }

    public void setJoiningDate(LocalDate joiningDate) {
        this.joiningDate = joiningDate;
    }

    public LocalDate getExitDate() {
        return exitDate;
    }

    public void setExitDate(LocalDate exitDate) {
        this.exitDate = exitDate;
    }

    public BigDecimal getBaseSalary() {
        return baseSalary;
    }

    public void setBaseSalary(BigDecimal baseSalary) {
        this.baseSalary = baseSalary;
    }

    public BigDecimal getExperienceYears() {
        return experienceYears;
    }

    public void setExperienceYears(BigDecimal experienceYears) {
        this.experienceYears = experienceYears;
    }

    public String[] getSkills() {
        return skills;
    }

    public void setSkills(String[] skills) {
        this.skills = skills;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
