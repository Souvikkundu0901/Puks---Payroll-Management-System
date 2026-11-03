package com.octopyder.payroll.people;

import com.octopyder.payroll.attendance.LeaveQueryService;
import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.people.dto.AddEmployeeRequest;
import com.octopyder.payroll.people.dto.BankAccountDto;
import com.octopyder.payroll.people.dto.BankAccountRequest;
import com.octopyder.payroll.people.dto.CreateEmployeeResponse;
import com.octopyder.payroll.people.dto.DeductionsRequest;
import com.octopyder.payroll.people.dto.EmployeeDto;
import com.octopyder.payroll.people.dto.EmployeePayrollDetails;
import com.octopyder.payroll.people.dto.OffboardRequest;
import com.octopyder.payroll.people.dto.UpdateEmployeeRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import com.octopyder.payroll.common.CsvUtil;
import com.octopyder.payroll.mail.MailService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmployeeService {

    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final DepartmentRepository departmentRepository;
    private final BankAccountRepository bankAccountRepository;
    private final EmployeeDeductionRepository deductionRepository;
    private final EmployeeDocumentRepository documentRepository;
    private final CodeGeneratorService codeGeneratorService;
    private final EncryptionService encryptionService;
    private final TokenService tokenService;
    private final TeamScope teamScope;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final LeaveQueryService leaveQueryService;
    private final MailService mailService;
    private final TransactionTemplate transactionTemplate;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final SecureRandom secureRandom = new SecureRandom();

    public EmployeeService(
        UserRepository userRepository,
        EmployeeProfileRepository profileRepository,
        DepartmentRepository departmentRepository,
        BankAccountRepository bankAccountRepository,
        EmployeeDeductionRepository deductionRepository,
        EmployeeDocumentRepository documentRepository,
        CodeGeneratorService codeGeneratorService,
        EncryptionService encryptionService,
        TokenService tokenService,
        TeamScope teamScope,
        AuditService auditService,
        BusinessClock businessClock,
        LeaveQueryService leaveQueryService,
        MailService mailService,
        PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.departmentRepository = departmentRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.deductionRepository = deductionRepository;
        this.documentRepository = documentRepository;
        this.codeGeneratorService = codeGeneratorService;
        this.encryptionService = encryptionService;
        this.tokenService = tokenService;
        this.teamScope = teamScope;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.leaveQueryService = leaveQueryService;
        this.mailService = mailService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PageResponse<EmployeeDto> listEmployees(
        CurrentUser currentUser,
        String query,
        UUID departmentId,
        UUID managerId,
        UserStatus status,
        Role role,
        int page,
        int size
    ) {
        int pageNo = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);

        List<User> allUsers = userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<EmployeeDto> filtered = new ArrayList<>();

        for (User u : allUsers) {
            if (u.getDeletedAt() != null) {
                continue;
            }
            // Scoping: Managers see only their own team
            if (currentUser.role() == Role.MANAGER && (u.getManagerId() == null || !u.getManagerId().equals(currentUser.id()))) {
                continue;
            }
            if (status != null && u.getStatus() != status) {
                continue;
            }
            if (role != null && u.getRole() != role) {
                continue;
            }
            if (managerId != null && (u.getManagerId() == null || !u.getManagerId().equals(managerId))) {
                continue;
            }

            EmployeeProfile profile = profileRepository.findById(u.getId()).orElse(null);
            if (departmentId != null && (profile == null || !departmentId.equals(profile.getDepartmentId()))) {
                continue;
            }
            if (query != null && !query.isBlank()) {
                String q = query.trim().toLowerCase();
                String name = profile != null ? (profile.getFirstName() + " " + profile.getLastName()).toLowerCase() : "";
                if (!u.getEmail().toLowerCase().contains(q) && !u.getCode().toLowerCase().contains(q) && !name.contains(q)) {
                    continue;
                }
            }

            filtered.add(toDto(u, profile));
        }

        int total = filtered.size();
        int start = Math.min(pageNo * pageSize, total);
        int end = Math.min(start + pageSize, total);
        List<EmployeeDto> pagedList = filtered.subList(start, end);
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;

        return new PageResponse<>(pagedList, pageNo, pageSize, (long) total, totalPages);
    }

    public CreateEmployeeResponse createEmployee(AddEmployeeRequest request, HttpServletRequest httpRequest) {
        return createEmployee(request, httpRequest, true);
    }

    public CreateEmployeeResponse createEmployee(AddEmployeeRequest request, HttpServletRequest httpRequest, boolean sendEmail) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new AppException(HttpStatus.CONFLICT, "USER_EXISTS", "User with this email already exists");
        }

        String code = request.role() == Role.ADMIN
            ? codeGeneratorService.nextAdminCode()
            : (request.role() == Role.MANAGER ? codeGeneratorService.nextManagerCode() : codeGeneratorService.nextEmployeeCode());

        String rawPassword = request.password() != null && !request.password().isBlank()
            ? request.password()
            : generateTemporaryPassword();

        User savedUser = transactionTemplate.execute(status -> {
            Instant now = businessClock.instant();
            User user = new User();
            user.setCode(code);
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setRole(request.role() != null ? request.role() : Role.EMPLOYEE);
            user.setStatus(UserStatus.ACTIVE);
            user.setMustChangePassword(true);
            user.setTemporaryPasswordExpiresAt(now.plus(Duration.ofDays(7)));
            user.setManagerId(request.managerId());
            user.setTokenVersion(1);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);

            User persistedUser = userRepository.save(user);

            EmployeeProfile profile = new EmployeeProfile(
                persistedUser.getId(),
                request.firstName().trim(),
                request.lastName().trim(),
                request.designation().trim(),
                request.baseSalary() != null ? request.baseSalary() : BigDecimal.ZERO
            );
            profile.setGender(request.gender());
            profile.setDateOfBirth(request.dateOfBirth());
            profile.setPhone(request.phone());
            profile.setAddress(request.address());
            profile.setBloodGroup(request.bloodGroup());
            profile.setEmergencyContact(request.emergencyContact());
            profile.setMaritalStatus(request.maritalStatus());
            profile.setDepartmentId(request.departmentId());
            profile.setEmploymentType(request.employmentType() != null ? request.employmentType() : "FULL_TIME");
            profile.setJoiningDate(request.joiningDate() != null ? request.joiningDate() : businessClock.today());
            profile.setExperienceYears(request.experienceYears() != null ? request.experienceYears() : BigDecimal.ZERO);
            if (request.skills() != null) {
                profile.setSkills(request.skills());
            }
            profile.setUpdatedAt(now);

            profileRepository.save(profile);

            auditService.log("CREATE_EMPLOYEE", "USER", persistedUser.getId().toString(), null, "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}");

            return persistedUser;
        });

        boolean emailSent = false;
        if (sendEmail && savedUser != null) {
            try {
                emailSent = mailService.sendLoginCredentials(savedUser.getEmail(), rawPassword);
            } catch (Exception e) {
                emailSent = false;
            }
            auditService.log("USER_EMAIL_NOTIFICATION", "USER", savedUser.getId().toString(), null, "{\"emailSent\":" + emailSent + "}");
        }

        return new CreateEmployeeResponse(
            savedUser.getId(),
            savedUser.getCode(),
            savedUser.getEmail(),
            rawPassword,
            true,
            emailSent
        );
    }

    public EmployeeDto getEmployee(CurrentUser currentUser, UUID id) {
        teamScope.assertCanAccess(currentUser, id);

        User user = userRepository.findById(id)
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Employee not found"));

        EmployeeProfile profile = profileRepository.findById(id).orElse(null);
        return toDto(user, profile);
    }

    @Transactional
    public EmployeeDto updateEmployee(UUID id, UpdateEmployeeRequest request) {
        User user = userRepository.findById(id)
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Employee not found"));

        if (request.managerId() != null) {
            user.setManagerId(request.managerId());
        }
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        user.setUpdatedAt(businessClock.instant());
        userRepository.save(user);

        EmployeeProfile profile = profileRepository.findById(id).orElseGet(() -> new EmployeeProfile(id, "Unknown", "User", "Employee", BigDecimal.ZERO));
        if (request.firstName() != null) profile.setFirstName(request.firstName());
        if (request.lastName() != null) profile.setLastName(request.lastName());
        if (request.phone() != null) profile.setPhone(request.phone());
        if (request.address() != null) profile.setAddress(request.address());
        if (request.gender() != null) profile.setGender(request.gender());
        if (request.dateOfBirth() != null) profile.setDateOfBirth(request.dateOfBirth());
        if (request.bloodGroup() != null) profile.setBloodGroup(request.bloodGroup());
        if (request.emergencyContact() != null) profile.setEmergencyContact(request.emergencyContact());
        if (request.maritalStatus() != null) profile.setMaritalStatus(request.maritalStatus());
        if (request.departmentId() != null) profile.setDepartmentId(request.departmentId());
        if (request.designation() != null) profile.setDesignation(request.designation());
        if (request.employmentType() != null) profile.setEmploymentType(request.employmentType());
        if (request.joiningDate() != null) profile.setJoiningDate(request.joiningDate());
        if (request.baseSalary() != null) profile.setBaseSalary(request.baseSalary());
        if (request.experienceYears() != null) profile.setExperienceYears(request.experienceYears());
        if (request.skills() != null) profile.setSkills(request.skills());

        profile.setUpdatedAt(businessClock.instant());
        profileRepository.save(profile);

        auditService.log("UPDATE_EMPLOYEE", "USER", id.toString(), null, "{\"email\":\"" + user.getEmail() + "\"}");

        return toDto(user, profile);
    }

    @Transactional
    public EmployeeDto offboardEmployee(UUID id, OffboardRequest request) {
        User user = userRepository.findById(id)
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Employee not found"));

        // Last-admin protection (BR-S4)
        if (user.getRole() == Role.ADMIN && userRepository.countByRoleAndStatusAndDeletedAtIsNull(Role.ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new AppException(HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED", "Cannot offboard the last active Administrator");
        }

        // Manager cannot be offboarded if they have team members (BR-O2)
        if (user.getRole() == Role.MANAGER && userRepository.countByManagerIdAndDeletedAtIsNull(id) > 0) {
            throw new AppException(HttpStatus.CONFLICT, "MANAGER_HAS_TEAM", "Cannot offboard a manager with assigned team members");
        }

        user.setStatus(request.status());
        user.setTokenVersion(user.getTokenVersion() + 1); // Immediate token invalidation (BR-S5)
        user.setUpdatedAt(businessClock.instant());
        userRepository.save(user);

        tokenService.revokeAllForUser(id);

        EmployeeProfile profile = profileRepository.findById(id).orElse(null);
        if (profile != null) {
            profile.setExitDate(request.exitDate() != null ? request.exitDate() : LocalDate.now());
            profile.setUpdatedAt(businessClock.instant());
            profileRepository.save(profile);
        }

        auditService.log("OFFBOARD_EMPLOYEE", "USER", id.toString(), null, "{\"status\":\"" + request.status() + "\"}");

        return toDto(user, profile);
    }

    public CreateEmployeeResponse resetPassword(UUID id) {
        String tempPassword = generateTemporaryPassword();

        User user = transactionTemplate.execute(status -> {
            User u = userRepository.findById(id)
                .filter(usr -> usr.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Employee not found"));

            u.setPasswordHash(passwordEncoder.encode(tempPassword));
            u.setMustChangePassword(true);
            u.setTemporaryPasswordExpiresAt(businessClock.instant().plus(Duration.ofDays(7)));
            u.setTokenVersion(u.getTokenVersion() + 1);
            u.setUpdatedAt(businessClock.instant());
            userRepository.save(u);

            tokenService.revokeAllForUser(id);
            auditService.log("RESET_PASSWORD", "USER", id.toString(), null, "Admin reset password");

            return u;
        });

        boolean emailSent = false;
        if (user != null) {
            try {
                emailSent = mailService.sendLoginCredentials(user.getEmail(), tempPassword);
            } catch (Exception e) {
                emailSent = false;
            }
            auditService.log("USER_EMAIL_NOTIFICATION", "USER", user.getId().toString(), null, "{\"emailSent\":" + emailSent + "}");
        }

        return new CreateEmployeeResponse(user.getId(), user.getCode(), user.getEmail(), tempPassword, true, emailSent);
    }

    @Transactional
    public void deleteEmployee(UUID id) {
        User user = userRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Employee not found"));

        if (user.getRole() == Role.ADMIN && userRepository.countByRoleAndStatusAndDeletedAtIsNull(Role.ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new AppException(HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED", "Cannot delete the last active Administrator");
        }

        if (user.getRole() == Role.MANAGER && userRepository.countByManagerIdAndDeletedAtIsNull(id) > 0) {
            throw new AppException(HttpStatus.CONFLICT, "MANAGER_HAS_TEAM", "Cannot delete a manager with active team members");
        }

        user.setDeletedAt(businessClock.instant());
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        tokenService.revokeAllForUser(id);
        auditService.log("DELETE_EMPLOYEE", "USER", id.toString(), null, "Employee soft deleted");
    }

    public BankAccountDto getBankAccount(CurrentUser currentUser, UUID id) {
        // Bank details: Admin or Self only! (Managers receive 403 Forbidden per D26)
        if (currentUser.role() != Role.ADMIN && !currentUser.id().equals(id)) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Managers cannot view bank details");
        }

        return bankAccountRepository.findById(id)
            .map(b -> new BankAccountDto(b.getBankName(), encryptionService.mask(b.getAccountLast4()), b.getIfsc()))
            .orElse(new BankAccountDto("", "", ""));
    }

    @Transactional
    public BankAccountDto updateBankAccount(UUID id, BankAccountRequest request) {
        String enc = encryptionService.encrypt(request.accountNumber().trim());
        String clean = request.accountNumber().trim();
        String last4 = clean.length() >= 4 ? clean.substring(clean.length() - 4) : clean;

        BankAccount account = bankAccountRepository.findById(id).orElseGet(() -> new BankAccount(id, "", "", "", ""));
        account.setBankName(request.bankName().trim());
        account.setAccountNumberEnc(enc);
        account.setAccountLast4(last4);
        account.setIfsc(request.ifsc().trim());
        account.setUpdatedAt(businessClock.instant());

        bankAccountRepository.save(account);
        auditService.log("UPDATE_BANK_ACCOUNT", "USER", id.toString(), null, "Bank details updated");

        return new BankAccountDto(account.getBankName(), encryptionService.mask(last4), account.getIfsc());
    }

    @Transactional
    public void updateDeductions(UUID id, DeductionsRequest request) {
        deductionRepository.deleteByUserId(id);
        if (request != null && request.deductions() != null) {
            for (DeductionsRequest.DeductionItem item : request.deductions()) {
                deductionRepository.save(new EmployeeDeduction(
                    id,
                    item.type(),
                    item.monthlyAmount() != null ? item.monthlyAmount() : BigDecimal.ZERO,
                    item.effectiveFrom() != null ? item.effectiveFrom() : LocalDate.now()
                ));
            }
        }
        auditService.log("UPDATE_DEDUCTIONS", "USER", id.toString(), null, "Deductions updated");
    }

    @Transactional(readOnly = true)
    public EmployeePayrollDetails getEmployeePayrollDetails(UUID userId, boolean includeAdmins) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found: " + userId));

        if (user.getDeletedAt() != null || user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_USER_NOT_ELIGIBLE", "User is inactive or deleted");
        }

        if (user.getRole() == Role.ADMIN && !includeAdmins) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_USER_NOT_ELIGIBLE", "Admin users are excluded from payroll");
        }

        EmployeeProfile profile = profileRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_USER_NOT_ELIGIBLE", "Employee profile not found"));

        if (profile.getBaseSalary() == null || profile.getBaseSalary().compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_NO_SALARY", "Employee has no positive base salary configured");
        }

        BigDecimal tds = BigDecimal.ZERO;
        BigDecimal pf = BigDecimal.ZERO;
        BigDecimal pt = BigDecimal.ZERO;
        List<EmployeeDeduction> deductions = deductionRepository.findByUserId(userId);
        if (deductions != null) {
            for (EmployeeDeduction d : deductions) {
                if (d.getMonthlyAmount() == null) continue;
                String type = d.getType() != null ? d.getType().trim().toUpperCase() : "";
                if ("TDS".equals(type)) {
                    tds = tds.add(d.getMonthlyAmount());
                } else if ("PF".equals(type)) {
                    pf = pf.add(d.getMonthlyAmount());
                } else if ("PT".equals(type)) {
                    pt = pt.add(d.getMonthlyAmount());
                }
            }
        }

        return new EmployeePayrollDetails(user, profile, tds, pf, pt);
    }

    public Map<String, Object> getSummary(CurrentUser currentUser, UUID id, String tab) {
        teamScope.assertCanAccess(currentUser, id);
        Map<String, Object> result = new HashMap<>();
        switch (tab.toLowerCase()) {
            case "attendance" -> {
                result.put("presentDays", 0);
                result.put("absentDays", 0);
                result.put("lateDays", 0);
                result.put("attendancePct", 100.0);
            }
            case "tasks" -> {
                result.put("totalTasks", 0);
                result.put("completedTasks", 0);
                result.put("pendingTasks", 0);
                result.put("completionPct", 100.0);
            }
            case "leaves" -> {
                result.put("availableBalance", 0);
                result.put("usedDays", 0);
                result.put("pendingRequests", 0);
            }
            case "payroll" -> {
                result.put("grossSalary", BigDecimal.ZERO);
                result.put("netSalary", BigDecimal.ZERO);
                result.put("retainedAmount", BigDecimal.ZERO);
            }
            case "documents" -> {
                long docCount = documentRepository.findByUserIdOrderByUploadedAtDesc(id).size();
                result.put("documentCount", docCount);
            }
            default -> throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_TAB", "Unknown summary tab: " + tab);
        }
        return result;
    }

    public void exportEmployeesCsv(OutputStream out) {
        List<User> users = userRepository.findByRoleAndDeletedAtIsNull(Role.EMPLOYEE);
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader("Code", "Name", "Email", "Department", "Designation", "Status").build())) {

            for (User u : users) {
                EmployeeProfile p = profileRepository.findById(u.getId()).orElse(null);
                String name = p != null ? p.getFirstName() + " " + p.getLastName() : "";
                String dept = "";
                if (p != null && p.getDepartmentId() != null) {
                    dept = departmentRepository.findById(p.getDepartmentId()).map(Department::getName).orElse("");
                }
                String desig = p != null ? p.getDesignation() : "";

                printer.printRecord(
                    CsvUtil.sanitizeCell(u.getCode()),
                    CsvUtil.sanitizeCell(name),
                    CsvUtil.sanitizeCell(u.getEmail()),
                    CsvUtil.sanitizeCell(dept),
                    CsvUtil.sanitizeCell(desig),
                    CsvUtil.sanitizeCell(u.getStatus().name())
                );
            }
            printer.flush();
        } catch (Exception e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "CSV_EXPORT_ERROR", "Failed to export employees CSV");
        }
    }

    @Transactional
    public int importEmployeesCsv(InputStream in) {
        int count = 0;
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true).build())) {

            for (CSVRecord record : parser) {
                String email = record.get("Email");
                if (email == null || email.isBlank() || userRepository.findByEmailIgnoreCase(email).isPresent()) {
                    continue;
                }
                String name = record.isMapped("Name") ? record.get("Name") : "Employee";
                String[] parts = name.split(" ", 2);
                String first = parts[0];
                String last = parts.length > 1 ? parts[1] : "";
                String desig = record.isMapped("Designation") ? record.get("Designation") : "Software Engineer";

                AddEmployeeRequest req = new AddEmployeeRequest(
                    first, last, email, null, null, null, null, null, null, null,
                    null, desig, "FULL_TIME", LocalDate.now(), null, BigDecimal.valueOf(50000), BigDecimal.ZERO, null, Role.EMPLOYEE, null
                );
                createEmployee(req, null, false);
                count++;
            }
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "CSV_IMPORT_ERROR", "Failed to parse CSV file: " + e.getMessage());
        }
        return count;
    }

    private EmployeeDto toDto(User user, EmployeeProfile profile) {
        String deptName = "";
        if (profile != null && profile.getDepartmentId() != null) {
            deptName = departmentRepository.findById(profile.getDepartmentId()).map(Department::getName).orElse("");
        }

        String mgrName = "";
        if (user.getManagerId() != null) {
            mgrName = profileRepository.findById(user.getManagerId())
                .map(p -> p.getFirstName() + " " + p.getLastName())
                .orElse("");
        }

        String fullName = profile != null ? profile.getFirstName() + " " + profile.getLastName() : user.getCode();
        String photoUrl = profile != null && profile.getPhotoKey() != null ? "/api/v1/documents/stream/" + profile.getPhotoKey() : null;

        boolean onLeaveToday = false;
        if (leaveQueryService != null) {
            LocalDate today = businessClock.instant().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
            onLeaveToday = leaveQueryService.hasApprovedLeave(user.getId(), today);
        }

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
            mgrName,
            profile != null ? profile.getBaseSalary() : BigDecimal.ZERO,
            profile != null ? profile.getExperienceYears() : BigDecimal.ZERO,
            profile != null ? profile.getSkills() : new String[0],
            user.getRole(),
            user.getStatus(),
            onLeaveToday,
            photoUrl
        );
    }

    private String generateTemporaryPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*";
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
