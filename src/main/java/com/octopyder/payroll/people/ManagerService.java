package com.octopyder.payroll.people;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.people.dto.AddEmployeeRequest;
import com.octopyder.payroll.people.dto.AddManagerRequest;
import com.octopyder.payroll.people.dto.AssignTeamRequest;
import com.octopyder.payroll.people.dto.CreateEmployeeResponse;
import com.octopyder.payroll.people.dto.EmployeeDto;
import com.octopyder.payroll.people.dto.ManagerDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ManagerService {

    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeService employeeService;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    public ManagerService(
        UserRepository userRepository,
        EmployeeProfileRepository profileRepository,
        DepartmentRepository departmentRepository,
        EmployeeService employeeService,
        TokenService tokenService,
        AuditService auditService,
        BusinessClock businessClock
    ) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.departmentRepository = departmentRepository;
        this.employeeService = employeeService;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.businessClock = businessClock;
    }

    public List<ManagerDto> listManagers() {
        List<User> managers = userRepository.findByRoleAndDeletedAtIsNull(Role.MANAGER);
        List<ManagerDto> dtos = new ArrayList<>();

        for (User u : managers) {
            EmployeeProfile p = profileRepository.findById(u.getId()).orElse(null);
            String name = p != null ? p.getFirstName() + " " + p.getLastName() : u.getCode();
            String deptName = "";
            if (p != null && p.getDepartmentId() != null) {
                deptName = departmentRepository.findById(p.getDepartmentId()).map(Department::getName).orElse("");
            }
            long teamSize = userRepository.countByManagerIdAndDeletedAtIsNull(u.getId());

            dtos.add(new ManagerDto(
                u.getId(),
                u.getCode(),
                name,
                u.getEmail(),
                p != null ? p.getPhone() : null,
                p != null ? p.getDepartmentId() : null,
                deptName,
                p != null ? p.getDesignation() : "Manager",
                p != null ? p.getExperienceYears() : BigDecimal.ZERO,
                teamSize,
                u.getStatus()
            ));
        }
        return dtos;
    }

    public ManagerDto getManager(UUID id) {
        User u = userRepository.findById(id)
            .filter(user -> user.getDeletedAt() == null && user.getRole() == Role.MANAGER)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Manager not found"));

        EmployeeProfile p = profileRepository.findById(id).orElse(null);
        String name = p != null ? p.getFirstName() + " " + p.getLastName() : u.getCode();
        String deptName = "";
        if (p != null && p.getDepartmentId() != null) {
            deptName = departmentRepository.findById(p.getDepartmentId()).map(Department::getName).orElse("");
        }
        long teamSize = userRepository.countByManagerIdAndDeletedAtIsNull(u.getId());

        return new ManagerDto(
            u.getId(),
            u.getCode(),
            name,
            u.getEmail(),
            p != null ? p.getPhone() : null,
            p != null ? p.getDepartmentId() : null,
            deptName,
            p != null ? p.getDesignation() : "Manager",
            p != null ? p.getExperienceYears() : BigDecimal.ZERO,
            teamSize,
            u.getStatus()
        );
    }

    public CreateEmployeeResponse createManager(AddManagerRequest request) {
        AddEmployeeRequest empReq = new AddEmployeeRequest(
            request.firstName(),
            request.lastName(),
            request.email(),
            request.phone(),
            null,
            null,
            null,
            null,
            null,
            null,
            request.departmentId(),
            request.designation(),
            "FULL_TIME",
            LocalDate.now(),
            null,
            request.baseSalary() != null ? request.baseSalary() : BigDecimal.valueOf(80000),
            request.experienceYears() != null ? request.experienceYears() : BigDecimal.ZERO,
            null,
            Role.MANAGER,
            null
        );
        return employeeService.createEmployee(empReq, null);
    }

    public List<EmployeeDto> getTeam(CurrentUser currentUser, UUID managerId) {
        if (currentUser.role() != Role.ADMIN && !currentUser.id().equals(managerId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Cannot view another manager's team");
        }

        List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(managerId);
        List<EmployeeDto> dtos = new ArrayList<>();
        for (User u : team) {
            EmployeeProfile p = profileRepository.findById(u.getId()).orElse(null);
            dtos.add(toEmployeeDto(u, p));
        }
        return dtos;
    }

    @Transactional
    public void assignTeam(UUID managerId, AssignTeamRequest request) {
        userRepository.findByIdForUpdate(managerId)
            .filter(u -> u.getRole() == Role.MANAGER)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Manager not found"));

        if (request != null && request.employeeIds() != null) {
            for (UUID empId : request.employeeIds()) {
                userRepository.findById(empId).ifPresent(emp -> {
                    emp.setManagerId(managerId);
                    emp.setUpdatedAt(businessClock.instant());
                    userRepository.save(emp);
                });
            }
        }
        auditService.log("ASSIGN_TEAM", "MANAGER", managerId.toString(), null, "Team updated");
    }

    @Transactional
    public void removeFromTeam(UUID managerId, UUID employeeId) {
        User employee = userRepository.findById(employeeId)
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Employee not found"));

        if (managerId.equals(employee.getManagerId())) {
            employee.setManagerId(null); // BR-O1: removing employee from team sets manager_id to null
            employee.setUpdatedAt(businessClock.instant());
            userRepository.save(employee);
            auditService.log("REMOVE_FROM_TEAM", "MANAGER", managerId.toString(), null, "{\"employeeId\":\"" + employeeId + "\"}");
        }
    }

    @Transactional
    public void deleteManager(UUID managerId) {
        User manager = userRepository.findByIdForUpdate(managerId)
            .filter(u -> u.getRole() == Role.MANAGER)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Manager not found"));

        long teamCount = userRepository.countByManagerIdAndDeletedAtIsNull(managerId);
        if (teamCount > 0) {
            throw new AppException(HttpStatus.CONFLICT, "MANAGER_HAS_TEAM", "Cannot delete a manager with active team members");
        }

        manager.setDeletedAt(businessClock.instant());
        manager.setTokenVersion(manager.getTokenVersion() + 1);
        userRepository.save(manager);

        tokenService.revokeAllForUser(managerId);
        auditService.log("DELETE_MANAGER", "USER", managerId.toString(), null, "Manager deleted");
    }

    private EmployeeDto toEmployeeDto(User user, EmployeeProfile profile) {
        String deptName = "";
        if (profile != null && profile.getDepartmentId() != null) {
            deptName = departmentRepository.findById(profile.getDepartmentId()).map(Department::getName).orElse("");
        }

        String fullName = profile != null ? profile.getFirstName() + " " + profile.getLastName() : user.getCode();
        String photoUrl = profile != null && profile.getPhotoKey() != null ? "/api/v1/documents/stream/" + profile.getPhotoKey() : null;

        return new EmployeeDto(
            user.getId(),
            user.getCode(),
            profile != null ? profile.getFirstName() : "",
            profile != null ? profile.getLastName() : "",
            fullName,
            user.getEmail(),
            profile != null ? profile.getPhone() : null,
            profile != null ? profile.getAddress() : null,
            profile != null ? profile.getGender() : null,
            profile != null ? profile.getDateOfBirth() : null,
            profile != null ? profile.getBloodGroup() : null,
            profile != null ? profile.getEmergencyContact() : null,
            profile != null ? profile.getMaritalStatus() : null,
            profile != null ? profile.getDepartmentId() : null,
            deptName,
            profile != null ? profile.getDesignation() : "",
            profile != null ? profile.getEmploymentType() : "FULL_TIME",
            profile != null ? profile.getJoiningDate() : null,
            profile != null ? profile.getExitDate() : null,
            user.getManagerId(),
            "",
            profile != null ? profile.getBaseSalary() : BigDecimal.ZERO,
            profile != null ? profile.getExperienceYears() : BigDecimal.ZERO,
            profile != null ? profile.getSkills() : new String[0],
            user.getRole(),
            user.getStatus(),
            false,
            photoUrl
        );
    }
}
