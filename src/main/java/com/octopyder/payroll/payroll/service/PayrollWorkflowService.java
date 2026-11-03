package com.octopyder.payroll.payroll.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.notification.NotificationService;
import com.octopyder.payroll.notification.NotificationType;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.payroll.PayrollLine;
import com.octopyder.payroll.payroll.PayrollLineKind;
import com.octopyder.payroll.payroll.PayrollLineRepository;
import com.octopyder.payroll.payroll.PayrollRecord;
import com.octopyder.payroll.payroll.PayrollRecordRepository;
import com.octopyder.payroll.payroll.PayrollRecordStatus;
import com.octopyder.payroll.payroll.PayrollRetention;
import com.octopyder.payroll.payroll.PayrollRetentionRepository;
import com.octopyder.payroll.payroll.PayrollRun;
import com.octopyder.payroll.payroll.PayrollRunRepository;
import com.octopyder.payroll.payroll.PayrollRunStatus;
import com.octopyder.payroll.payroll.RetentionStatus;
import com.octopyder.payroll.payroll.calculator.PayrollCalculator;
import com.octopyder.payroll.payroll.calculator.PayrollInputs;
import com.octopyder.payroll.payroll.calculator.PayrollResult;
import com.octopyder.payroll.payroll.dto.BankBriefDto;
import com.octopyder.payroll.payroll.dto.DepartmentSummaryDto;
import com.octopyder.payroll.payroll.dto.EmployeeBriefDto;
import com.octopyder.payroll.payroll.dto.PayrollLineDto;
import com.octopyder.payroll.payroll.dto.PayrollRecordResponse;
import com.octopyder.payroll.payroll.dto.PayrollRejectRequest;
import com.octopyder.payroll.payroll.dto.PayrollReleaseAllRequest;
import com.octopyder.payroll.payroll.dto.PayrollReleaseAllResponse;
import com.octopyder.payroll.payroll.dto.PayrollReleaseItemResult;
import com.octopyder.payroll.payroll.dto.PayrollReleaseRequest;
import com.octopyder.payroll.payroll.dto.PayrollRunRequest;
import com.octopyder.payroll.payroll.dto.PayrollRunResponse;
import com.octopyder.payroll.payroll.dto.PayrollSummaryResponse;
import com.octopyder.payroll.payroll.dto.RetentionResponse;
import com.octopyder.payroll.people.Department;
import com.octopyder.payroll.people.DepartmentRepository;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.EmployeeService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.dto.BankAccountDto;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PayrollWorkflowService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PayrollWorkflowService.class);
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private final ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> monthLocks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PayrollRunRepository runRepository;
    private final PayrollRecordRepository recordRepository;
    private final PayrollLineRepository lineRepository;
    private final PayrollRetentionRepository retentionRepository;
    private final PayrollInputsAssembler assembler;
    private final PayslipPdfService payslipPdfService;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeService employeeService;
    private final SettingsService settingsService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final PlatformTransactionManager transactionManager;
    private final com.octopyder.payroll.report.ReportService reportService;

    public PayrollWorkflowService(
        PayrollRunRepository runRepository,
        PayrollRecordRepository recordRepository,
        PayrollLineRepository lineRepository,
        PayrollRetentionRepository retentionRepository,
        PayrollInputsAssembler assembler,
        PayslipPdfService payslipPdfService,
        UserRepository userRepository,
        EmployeeProfileRepository profileRepository,
        DepartmentRepository departmentRepository,
        EmployeeService employeeService,
        SettingsService settingsService,
        NotificationService notificationService,
        AuditService auditService,
        BusinessClock businessClock,
        PlatformTransactionManager transactionManager,
        com.octopyder.payroll.report.ReportService reportService
    ) {
        this.runRepository = runRepository;
        this.recordRepository = recordRepository;
        this.lineRepository = lineRepository;
        this.retentionRepository = retentionRepository;
        this.assembler = assembler;
        this.payslipPdfService = payslipPdfService;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.departmentRepository = departmentRepository;
        this.employeeService = employeeService;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.transactionManager = transactionManager;
        this.reportService = reportService;
    }

    private YearMonth parseAndValidateMonth(String monthStr) {
        if (monthStr == null || !MONTH_PATTERN.matcher(monthStr.trim()).matches()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PAYROLL_INVALID_MONTH", "Month must be in YYYY-MM format");
        }
        YearMonth ym = YearMonth.parse(monthStr.trim());
        if (ym.getYear() < 2000 || ym.getYear() > 2100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PAYROLL_INVALID_MONTH", "Year must be between 2000 and 2100");
        }
        return ym;
    }

    public PayrollRunResponse generateRun(CurrentUser currentUser, PayrollRunRequest request) {
        if (currentUser == null || !currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Admin can generate payroll runs");
        }

        YearMonth ym = parseAndValidateMonth(request.month());
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        String monthKey = ym.toString();

        java.util.concurrent.locks.ReentrantLock lock = monthLocks.computeIfAbsent(monthKey, k -> new java.util.concurrent.locks.ReentrantLock());
        if (!lock.tryLock()) {
            throw new AppException(HttpStatus.CONFLICT, "PAYROLL_RUN_IN_PROGRESS", "A payroll run for " + monthKey + " is already in progress");
        }
        try {
            // Cutoff check
            LocalDate today = businessClock.today();
            boolean allowMidMonth = settingsService.getBoolean("payroll.allowMidMonthGeneration", false);
            if (!allowMidMonth && !today.isAfter(monthEnd)) {
                throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_MONTH_NOT_ENDED",
                    "Payroll can only be generated after month end (" + monthEnd + "). Set payroll.allowMidMonthGeneration=true to override.");
            }

            // Department validation
            if (request.departmentId() != null) {
                departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "DEPARTMENT_NOT_FOUND", "Department not found"));
            }

            // Eligible employees: active, role EMPLOYEE or MANAGER (and ADMIN if includeAdmins=true), base_salary > 0
            boolean includeAdmins = settingsService.getBoolean("payroll.includeAdmins", false);
            List<User> eligibleUsers = findEligibleUsers(request.departmentId(), includeAdmins, monthStart, monthEnd);

            if (eligibleUsers.isEmpty()) {
                throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_NO_ELIGIBLE_USERS", "No eligible employees found for this payroll run");
            }

            // Check if records already exist
            List<PayrollRecord> existingRecords = recordRepository.findByMonth(monthStart);
            if (request.departmentId() != null) {
                Set<UUID> userIds = eligibleUsers.stream().map(User::getId).collect(Collectors.toSet());
                existingRecords = existingRecords.stream().filter(r -> userIds.contains(r.getUserId())).toList();
            }

            boolean isRecalculate = Boolean.TRUE.equals(request.recalculate());
            if (!existingRecords.isEmpty() && !isRecalculate) {
                throw new AppException(HttpStatus.CONFLICT, "PAYROLL_ALREADY_GENERATED", "Payroll has already been generated for " + monthKey + ". Use recalculate=true to recalculate open records.");
            }

            // Create Run entity
            String paramsJson;
            try {
                paramsJson = objectMapper.writeValueAsString(Map.of(
                    "treatPendingAsPresent", Boolean.TRUE.equals(request.treatPendingAsPresent()),
                    "recalculate", isRecalculate,
                    "departmentId", request.departmentId() != null ? request.departmentId().toString() : ""
                ));
            } catch (Exception e) {
                paramsJson = "{}";
            }

            PayrollRun run = new PayrollRun(monthStart, request.departmentId(), PayrollRunStatus.OPEN, currentUser.id(), paramsJson, businessClock.instant());
            PayrollRun savedRun = runRepository.save(run);

            int generatedCount = 0;
            int recalculatedCount = 0;
            int skippedCount = 0;
            int failedCount = 0;
            List<String> skipped = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

            for (User user : eligibleUsers) {
                try {
                    String resultStatus = txTemplate.execute(status -> processEmployeePayroll(
                        user, monthStart, ym, savedRun.getId(), isRecalculate, Boolean.TRUE.equals(request.treatPendingAsPresent())
                    ));

                    if ("GENERATED".equals(resultStatus)) {
                        generatedCount++;
                    } else if ("RECALCULATED".equals(resultStatus)) {
                        recalculatedCount++;
                    } else if (resultStatus != null && resultStatus.startsWith("SKIPPED:")) {
                        skippedCount++;
                        skipped.add(resultStatus.substring(8));
                    }
                } catch (Exception ex) {
                    log.error("Failed to process payroll for {}", user.getCode(), ex);
                    failedCount++;
                    failed.add(user.getCode() + ": " + ex.getMessage());
                }
            }

            auditService.log("PAYROLL_RUN_GENERATE", "PAYROLL_RUN", savedRun.getId().toString(), null,
                "Payroll run generated for " + monthKey + " (Generated: " + generatedCount + ", Recalculated: " + recalculatedCount + ", Skipped: " + skippedCount + ", Failed: " + failedCount + ")");

            return new PayrollRunResponse(
                savedRun.getId(),
                monthKey,
                eligibleUsers.size(),
                generatedCount,
                recalculatedCount,
                skippedCount,
                failedCount,
                skipped,
                failed
            );
        } finally {
            lock.unlock();
        }
    }

    private String processEmployeePayroll(
        User user,
        LocalDate monthStart,
        YearMonth ym,
        UUID runId,
        boolean recalculate,
        boolean treatPendingAsPresent
    ) {
        Optional<PayrollRecord> existingOpt = recordRepository.findByUserIdAndMonth(user.getId(), monthStart);
        if (existingOpt.isPresent()) {
            PayrollRecord existing = existingOpt.get();
            if (!recalculate) {
                return "SKIPPED:" + user.getCode() + " record already exists";
            }
            if (existing.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
                return "SKIPPED:" + user.getCode() + " has paid_amount > 0 (frozen)";
            }
            if (existing.getStatus() == PayrollRecordStatus.APPROVED || existing.getStatus() == PayrollRecordStatus.RELEASED) {
                return "SKIPPED:" + user.getCode() + " is already " + existing.getStatus();
            }

            // Unlink any retentions released in this record
            List<PayrollRetention> previouslyLinked = retentionRepository.findByReleasedInRecordId(existing.getId());
            for (PayrollRetention pr : previouslyLinked) {
                pr.setReleasedInRecordId(null);
                if (pr.getDays() == 2) {
                    pr.setStatus(RetentionStatus.HELD);
                }
                retentionRepository.save(pr);
            }

            // Delete old lines
            lineRepository.deleteByRecordId(existing.getId());

            // Delete old held retention created by this record
            List<PayrollRetention> createdRetentions = retentionRepository.findByRecordId(existing.getId());
            for (PayrollRetention cr : createdRetentions) {
                if (cr.getStatus() == RetentionStatus.HELD) {
                    retentionRepository.delete(cr);
                }
            }
        }

        AssembledPayrollInputs assembled = assembler.assemble(user.getId(), ym.toString(), treatPendingAsPresent);

        // Check for prior unlinked retentions from earlier approved/released months
        List<PayrollRetention> unlinkedRetentions = retentionRepository.findByUserIdAndStatusForUpdate(user.getId(), RetentionStatus.HELD);
        List<PayrollRetention> manuallyReleased = retentionRepository.findByUserIdAndStatusForUpdate(user.getId(), RetentionStatus.RELEASED);

        BigDecimal retentionReleasedTotal = BigDecimal.ZERO;
        List<PayrollRetention> toLink = new ArrayList<>();

        // 2-day retentions auto-release
        for (PayrollRetention pr : unlinkedRetentions) {
            if (pr.getDays() == 2 && pr.getReleasedInRecordId() == null) {
                Optional<PayrollRecord> srcOpt = recordRepository.findById(pr.getRecordId());
                if (srcOpt.isPresent()) {
                    PayrollRecord src = srcOpt.get();
                    if (src.getMonth().isBefore(monthStart) && (src.getStatus() == PayrollRecordStatus.APPROVED || src.getStatus() == PayrollRecordStatus.RELEASED)) {
                        pr.setStatus(RetentionStatus.RELEASED);
                        retentionReleasedTotal = retentionReleasedTotal.add(pr.getAmount());
                        toLink.add(pr);
                    }
                }
            }
        }

        // 7-day retentions that were manually released
        for (PayrollRetention pr : manuallyReleased) {
            if (pr.getReleasedInRecordId() == null) {
                Optional<PayrollRecord> srcOpt = recordRepository.findById(pr.getRecordId());
                if (srcOpt.isPresent()) {
                    PayrollRecord src = srcOpt.get();
                    if (src.getMonth().isBefore(monthStart) && (src.getStatus() == PayrollRecordStatus.APPROVED || src.getStatus() == PayrollRecordStatus.RELEASED)) {
                        retentionReleasedTotal = retentionReleasedTotal.add(pr.getAmount());
                        toLink.add(pr);
                    }
                }
            }
        }

        PayrollResult result = PayrollCalculator.calculate(assembled.inputs());

        // Net = calculator net + released retention total (BR-P11, Change 4)
        BigDecimal netPayable = result.net().add(retentionReleasedTotal);
        BigDecimal tranche1 = result.defaultReleaseAmount().min(result.net());
        BigDecimal tranche2 = netPayable.subtract(tranche1);

        String breakupSetting = settingsService.getString("payroll.breakupPercent", "Basic Salary 50, HRA 20, Medical Allowance 10, Travel Allowance 10, Performance Bonus 10");

        PayrollRecord record = existingOpt.orElseGet(PayrollRecord::new);
        boolean isNew = (record.getId() == null);

        record.setRunId(runId);
        record.setUserId(user.getId());
        record.setMonth(monthStart);
        record.setGross(result.gross());
        record.setTasksTotal(assembled.inputs().tasksTotal());
        record.setTasksApproved(assembled.inputs().tasksApproved());
        record.setCompletionPct(result.completionPct());
        record.setRetentionDays(result.retentionDays());
        record.setRetentionAmount(result.retentionAmount());
        record.setAbsentDays(BigDecimal.valueOf(result.absentDays()));
        record.setAbsentDeduction(result.absentDeduction());
        record.setUnpaidLeaveDays(BigDecimal.valueOf(result.unpaidLeaveDays()));
        record.setUnpaidLeaveDeduction(result.unpaidLeaveDeduction());
        record.setUnpaidDays(BigDecimal.valueOf(result.absentDays() + result.unpaidLeaveDays()));
        record.setUnpaidDeduction(result.totalUnpaidDeduction());
        record.setTdsDeduction(result.tds());
        record.setPfDeduction(result.pf());
        record.setPtDeduction(result.pt());
        record.setStatutoryTotal(result.statutoryTotal());
        record.setRetentionReleasedAmount(retentionReleasedTotal);
        record.setNetPayable(netPayable);
        record.setNetClamped(result.isNetNegativeClamped());
        record.setPresentDays(assembled.presentDays());
        record.setWorkingDays(assembled.workingDays());
        record.setDefaultReleaseAmount(result.defaultReleaseAmount());
        record.setTaskLinkedAmount(result.taskLinkedAmount());
        record.setTaskUnlockedAmount(result.taskUnlockedAmount());
        record.setReleasedPct(result.releasedPct());
        record.setTranche1Amount(tranche1);
        record.setTranche2Amount(tranche2);
        record.setPaidAmount(BigDecimal.ZERO.setScale(2));
        record.setStatus(PayrollRecordStatus.GENERATED);
        record.setRejectNote(null);
        record.setApprovedBy(null);
        record.setApprovedAt(null);
        record.setReleasedBy(null);
        record.setReleasedAt(null);
        record.setUpdatedAt(businessClock.instant());

        // Snapshot creation
        Map<String, Object> snapshotMap = new HashMap<>();
        snapshotMap.put("employeeId", user.getId().toString());
        snapshotMap.put("employeeCode", user.getCode());
        snapshotMap.put("gross", result.gross());
        snapshotMap.put("tasksTotal", assembled.inputs().tasksTotal());
        snapshotMap.put("tasksApproved", assembled.inputs().tasksApproved());
        snapshotMap.put("absentDays", result.absentDays());
        snapshotMap.put("unpaidLeaveDays", result.unpaidLeaveDays());
        snapshotMap.put("tds", result.tds());
        snapshotMap.put("pf", result.pf());
        snapshotMap.put("pt", result.pt());
        snapshotMap.put("statutoryTotal", result.statutoryTotal());
        snapshotMap.put("retentionDays", result.retentionDays());
        snapshotMap.put("retentionAmount", result.retentionAmount());
        snapshotMap.put("retentionReleasedAmount", retentionReleasedTotal);
        snapshotMap.put("netPayable", netPayable);

        try {
            record.setSnapshot(objectMapper.writeValueAsString(snapshotMap));
        } catch (Exception e) {
            record.setSnapshot("{}");
        }

        PayrollRecord saved = recordRepository.save(record);

        var linesResult = PayrollLineGenerator.generateLines(
            saved.getId(),
            result.gross(),
            retentionReleasedTotal,
            result.absentDeduction(),
            result.unpaidLeaveDeduction(),
            result.tds(),
            result.pf(),
            result.pt(),
            result.retentionAmount(),
            breakupSetting
        );

        saved.setNetPayable(linesResult.netPayable());
        saved.setNetClamped(linesResult.netClamped());
        lineRepository.saveAll(linesResult.lines());

        for (PayrollRetention pr : toLink) {
            pr.setReleasedInRecordId(saved.getId());
            retentionRepository.save(pr);
        }

        if (result.retentionDays() > 0) {
            PayrollRetention ret = new PayrollRetention(saved.getId(), user.getId(), result.retentionDays(), result.retentionAmount(), RetentionStatus.HELD);
            retentionRepository.save(ret);
        }

        notificationService.createNotification(
            user.getId(),
            NotificationType.PAYROLL_GENERATED,
            "Payroll Generated: " + ym,
            "Your payroll record for " + ym + " has been generated.",
            "/employee/salary.html?month=" + ym
        );

        return isNew ? "GENERATED" : "RECALCULATED";
    }

    private List<User> findEligibleUsers(UUID departmentId, boolean includeAdmins, LocalDate monthStart, LocalDate monthEnd) {
        List<User> users = userRepository.findAll();
        List<User> eligible = new ArrayList<>();

        for (User u : users) {
            if (u.getStatus() != com.octopyder.payroll.people.UserStatus.ACTIVE || u.getDeletedAt() != null) {
                continue;
            }
            if (u.getRole() == Role.ADMIN && !includeAdmins) {
                continue;
            }
            if (u.getRole() != Role.EMPLOYEE && u.getRole() != Role.MANAGER && u.getRole() != Role.ADMIN) {
                continue;
            }

            Optional<EmployeeProfile> profOpt = profileRepository.findById(u.getId());
            if (profOpt.isEmpty()) continue;
            EmployeeProfile p = profOpt.get();

            if (p.getBaseSalary() == null || p.getBaseSalary().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (p.getJoiningDate() != null && p.getJoiningDate().isAfter(monthEnd)) {
                continue;
            }
            if (p.getExitDate() != null && p.getExitDate().isBefore(monthStart)) {
                continue;
            }
            if (departmentId != null && !departmentId.equals(p.getDepartmentId())) {
                continue;
            }

            eligible.add(u);
        }
        return eligible;
    }

    @Transactional
    public PayrollRecordResponse approveRecord(CurrentUser currentUser, UUID recordId) {
        PayrollRecord record = getRecordEntity(recordId);

        if (record.getStatus() != PayrollRecordStatus.GENERATED) {
            throw new AppException(HttpStatus.CONFLICT, "PAYROLL_INVALID_TRANSITION", "Only GENERATED payroll records can be approved");
        }

        if (record.getUserId().equals(currentUser.id())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SELF_APPROVAL", "Cannot approve own payroll record");
        }

        User employee = userRepository.findById(record.getUserId())
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Employee not found"));

        // Admin approves for managers or unassigned employees; manager approves for own team
        if (employee.getRole() == Role.MANAGER || employee.getManagerId() == null) {
            if (!currentUser.isAdmin()) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Manager payroll requires Admin approval");
            }
        } else {
            if (currentUser.isManager() && !currentUser.id().equals(employee.getManagerId())) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Cannot approve payroll for an employee outside your team");
            }
        }

        // Re-check inputs against snapshot (PAYROLL_STALE check)
        verifySnapshotIntegrity(record);

        record.setStatus(PayrollRecordStatus.APPROVED);
        record.setApprovedBy(currentUser.id());
        record.setApprovedAt(businessClock.instant());
        record.setUpdatedAt(businessClock.instant());
        PayrollRecord saved = recordRepository.save(record);

        auditService.log("PAYROLL_APPROVE", "PAYROLL_RECORD", saved.getId().toString(), PayrollRecordStatus.GENERATED.name(), PayrollRecordStatus.APPROVED.name());

        notificationService.createNotification(
            employee.getId(),
            NotificationType.PAYROLL_APPROVED,
            "Payroll Approved: " + YearMonth.from(saved.getMonth()),
            "Your payroll record has been approved.",
            "/employee/salary.html?month=" + YearMonth.from(saved.getMonth())
        );

        return toResponse(saved, currentUser);
    }

    @Transactional
    public PayrollRecordResponse rejectRecord(CurrentUser currentUser, UUID recordId, PayrollRejectRequest request) {
        if (request == null || request.note() == null || request.note().trim().isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Rejection note is required");
        }

        PayrollRecord record = getRecordEntity(recordId);

        if (record.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new AppException(HttpStatus.CONFLICT, "PAYROLL_ALREADY_PAID", "Cannot reject a payroll record with paid_amount > 0");
        }
        if (record.getStatus() != PayrollRecordStatus.GENERATED) {
            throw new AppException(HttpStatus.CONFLICT, "PAYROLL_INVALID_TRANSITION", "Only GENERATED payroll records can be rejected");
        }

        if (record.getUserId().equals(currentUser.id())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SELF_APPROVAL", "Cannot reject own payroll record");
        }

        User employee = userRepository.findById(record.getUserId())
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Employee not found"));

        if (employee.getRole() == Role.MANAGER || employee.getManagerId() == null) {
            if (!currentUser.isAdmin()) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Manager payroll decisions require Admin");
            }
        } else {
            if (currentUser.isManager() && !currentUser.id().equals(employee.getManagerId())) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Cannot reject payroll for an employee outside your team");
            }
        }

        record.setStatus(PayrollRecordStatus.REJECTED);
        record.setRejectNote(request.note().trim());
        record.setApprovedBy(null);
        record.setApprovedAt(null);
        record.setUpdatedAt(businessClock.instant());
        PayrollRecord saved = recordRepository.save(record);

        auditService.log("PAYROLL_REJECT", "PAYROLL_RECORD", saved.getId().toString(), PayrollRecordStatus.GENERATED.name(), PayrollRecordStatus.REJECTED.name());

        notificationService.createNotification(
            employee.getId(),
            NotificationType.PAYROLL_REJECTED,
            "Payroll Rejected: " + YearMonth.from(saved.getMonth()),
            "Your payroll was rejected: " + request.note().trim(),
            "/employee/salary.html?month=" + YearMonth.from(saved.getMonth())
        );

        return toResponse(saved, currentUser);
    }

    @Transactional
    public PayrollRecordResponse releaseDefault(CurrentUser currentUser, UUID recordId, PayrollReleaseRequest request) {
        if (!currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Admin can release payroll tranches");
        }
        validateReleaseRequest(request);

        PayrollRecord record = getRecordEntity(recordId);

        if (record.getStatus() != PayrollRecordStatus.GENERATED && record.getStatus() != PayrollRecordStatus.APPROVED) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_INVALID_TRANSITION", "Tranche 1 can only be released while GENERATED or APPROVED");
        }

        if (record.getTranche1PaidAt() != null || (record.getTranche1Amount().compareTo(BigDecimal.ZERO) > 0 && record.getPaidAmount().compareTo(record.getTranche1Amount()) >= 0)) {
            throw new AppException(HttpStatus.CONFLICT, "PAYROLL_ALREADY_PAID", "Tranche 1 has already been paid for this record");
        }

        verifySnapshotIntegrity(record);

        record.setPaidAmount(record.getTranche1Amount());
        record.setTranche1PaidAt(businessClock.instant());
        record.setTranche1PaymentDate(request.paymentDate());
        record.setTranche1PaymentRef(request.paymentRef().trim());
        record.setTranche1PaymentMethod(request.paymentMethod().trim());
        record.setUpdatedAt(businessClock.instant());
        PayrollRecord saved = recordRepository.save(record);

        auditService.log("PAYROLL_RELEASE_DEFAULT", "PAYROLL_RECORD", saved.getId().toString(), null, "Tranche 1 released: INR " + saved.getTranche1Amount());

        notificationService.createNotification(
            record.getUserId(),
            NotificationType.PAYROLL_PAID,
            "Tranche 1 Released: " + YearMonth.from(saved.getMonth()),
            "Tranche 1 of INR " + saved.getTranche1Amount() + " has been paid.",
            "/employee/salary.html?month=" + YearMonth.from(saved.getMonth())
        );

        return toResponse(saved, currentUser);
    }

    @Transactional
    public PayrollRecordResponse releaseRemainder(CurrentUser currentUser, UUID recordId, PayrollReleaseRequest request) {
        if (!currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Admin can release payroll");
        }
        validateReleaseRequest(request);

        PayrollRecord record = getRecordEntity(recordId);

        if (record.getStatus() == PayrollRecordStatus.RELEASED) {
            throw new AppException(HttpStatus.CONFLICT, "PAYROLL_ALREADY_RELEASED", "Payroll record is already fully released");
        }
        if (record.getStatus() != PayrollRecordStatus.APPROVED) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_NOT_APPROVED", "Payroll record must be APPROVED before releasing remainder");
        }

        verifySnapshotIntegrity(record);

        record.setPaidAmount(record.getNetPayable());
        record.setPaymentDate(request.paymentDate());
        record.setPaymentRef(request.paymentRef().trim());
        record.setPaymentMethod(request.paymentMethod().trim());
        record.setStatus(PayrollRecordStatus.RELEASED);
        record.setReleasedBy(currentUser.id());
        record.setReleasedAt(businessClock.instant());
        record.setUpdatedAt(businessClock.instant());
        PayrollRecord saved = recordRepository.save(record);

        // Check if run can be CLOSED
        List<PayrollRecord> runRecords = recordRepository.findByRunId(saved.getRunId());
        boolean allReleased = runRecords.stream().allMatch(r -> r.getStatus() == PayrollRecordStatus.RELEASED);
        if (allReleased) {
            runRepository.findById(saved.getRunId()).ifPresent(r -> {
                r.setStatus(PayrollRunStatus.CLOSED);
                runRepository.save(r);
            });
        }

        auditService.log("PAYROLL_RELEASE", "PAYROLL_RECORD", saved.getId().toString(), PayrollRecordStatus.APPROVED.name(), PayrollRecordStatus.RELEASED.name());

        notificationService.createNotification(
            record.getUserId(),
            NotificationType.PAYROLL_PAID,
            "Payroll Fully Released: " + YearMonth.from(saved.getMonth()),
            "Your payroll for " + YearMonth.from(saved.getMonth()) + " has been fully released.",
            "/employee/salary.html?month=" + YearMonth.from(saved.getMonth())
        );

        return toResponse(saved, currentUser);
    }

    @Transactional
    public PayrollReleaseAllResponse releaseAll(CurrentUser currentUser, PayrollReleaseAllRequest request) {
        if (!currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Admin can release all payroll");
        }
        if (request == null || request.paymentDate() == null || request.paymentRef() == null || request.paymentRef().isBlank() || request.paymentMethod() == null || request.paymentMethod().isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Payment date, reference, and method are required");
        }
        if (request.paymentDate().isAfter(businessClock.today())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PAYROLL_FUTURE_PAYMENT_DATE", "Payment date cannot be in the future");
        }
        if (request.paymentRef().trim().length() > 100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Payment reference must not exceed 100 characters");
        }
        if (request.paymentMethod().trim().length() > 50) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Payment method must not exceed 50 characters");
        }

        List<PayrollRecord> records;
        if (request.runId() != null) {
            records = recordRepository.findByRunId(request.runId());
        } else if (request.month() != null) {
            YearMonth ym = parseAndValidateMonth(request.month());
            records = recordRepository.findByMonth(ym.atDay(1));
        } else {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Either runId or month must be specified");
        }

        if (records.size() > 200) {
            records = records.subList(0, 200);
        }

        int releasedCount = 0;
        int tranche1Count = 0;
        int failedCount = 0;
        List<PayrollReleaseItemResult> results = new ArrayList<>();

        PayrollReleaseRequest singleReq = new PayrollReleaseRequest(request.paymentDate(), request.paymentRef(), request.paymentMethod());

        for (PayrollRecord r : records) {
            try {
                if (r.getStatus() == PayrollRecordStatus.APPROVED) {
                    BigDecimal toPay = r.getNetPayable().subtract(r.getPaidAmount());
                    releaseRemainder(currentUser, r.getId(), singleReq);
                    releasedCount++;
                    results.add(new PayrollReleaseItemResult(r.getId(), "RELEASED", toPay, null));
                } else if (r.getStatus() == PayrollRecordStatus.GENERATED && r.getTranche1PaidAt() == null) {
                    releaseDefault(currentUser, r.getId(), singleReq);
                    tranche1Count++;
                    results.add(new PayrollReleaseItemResult(r.getId(), "TRANCHE1_PAID", r.getTranche1Amount(), null));
                } else {
                    results.add(new PayrollReleaseItemResult(r.getId(), "SKIPPED", BigDecimal.ZERO, "Record status is " + r.getStatus()));
                }
            } catch (Exception ex) {
                failedCount++;
                results.add(new PayrollReleaseItemResult(r.getId(), "FAILED", BigDecimal.ZERO, ex.getMessage()));
            }
        }

        auditService.log("PAYROLL_RELEASE_ALL", "PAYROLL_RUN", request.runId() != null ? request.runId().toString() : request.month(), null,
            "Release all processed: " + records.size() + " records (Released: " + releasedCount + ", Tranche1: " + tranche1Count + ", Failed: " + failedCount + ")");

        return new PayrollReleaseAllResponse(records.size(), releasedCount, tranche1Count, failedCount, results);
    }

    @Transactional
    public RetentionResponse decideRetention(CurrentUser currentUser, UUID retentionId, boolean release, String note) {
        if (note == null || note.trim().isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Decision note is required for retention");
        }

        PayrollRetention retention = retentionRepository.findById(retentionId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "RETENTION_NOT_FOUND", "Retention record not found"));

        if (retention.getStatus() != RetentionStatus.HELD) {
            throw new AppException(HttpStatus.CONFLICT, "RETENTION_ALREADY_DECIDED", "Retention is already " + retention.getStatus());
        }

        if (retention.getDays() != 7) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_RETENTION_INVALID_DAYS", "Only 7-day retentions can be manually decided; 2-day retentions auto-release in next payroll run");
        }

        if (retention.getUserId().equals(currentUser.id())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SELF_APPROVAL", "Cannot decide own retention");
        }

        User employee = userRepository.findById(retention.getUserId())
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Employee not found"));

        if (currentUser.isManager() && !currentUser.id().equals(employee.getManagerId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Managers can only decide retention for their own team");
        }

        retention.setDecidedBy(currentUser.id());
        retention.setDecidedAt(businessClock.instant());
        retention.setNote(note.trim());
        retention.setStatus(release ? RetentionStatus.RELEASED : RetentionStatus.FORFEITED);

        PayrollRetention saved = retentionRepository.save(retention);

        auditService.log(
            release ? "RETENTION_RELEASE" : "RETENTION_FORFEIT",
            "PAYROLL_RETENTION",
            saved.getId().toString(),
            RetentionStatus.HELD.name(),
            saved.getStatus().name()
        );

        notificationService.createNotification(
            employee.getId(),
            release ? NotificationType.PAYROLL_APPROVED : NotificationType.PAYROLL_REJECTED,
            "Retention " + (release ? "Released" : "Forfeited"),
            "Your " + saved.getDays() + "-day retention of INR " + saved.getAmount() + " was " + saved.getStatus() + ": " + note.trim(),
            "/employee/salary.html"
        );

        return toRetentionResponse(saved, employee);
    }

    public List<RetentionResponse> getRetentions(CurrentUser currentUser, RetentionStatus status) {
        List<PayrollRetention> list;
        if (currentUser.isAdmin()) {
            list = (status != null)
                ? retentionRepository.findByStatusOrderByCreatedAtDesc(status)
                : retentionRepository.findAll();
        } else if (currentUser.isManager()) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            List<UUID> teamIds = team.stream().map(User::getId).toList();
            if (teamIds.isEmpty()) return Collections.emptyList();
            list = (status != null)
                ? retentionRepository.findByUserIdInAndStatusOrderByCreatedAtDesc(teamIds, status)
                : retentionRepository.findAll().stream().filter(r -> teamIds.contains(r.getUserId())).toList();
        } else {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees cannot view retention list");
        }

        return list.stream().map(r -> {
            User u = userRepository.findById(r.getUserId()).orElse(null);
            return toRetentionResponse(r, u);
        }).toList();
    }

    @Transactional
    public int bulkApproveTeamRecords(CurrentUser currentUser, String monthStr) {
        if (!currentUser.isManager() && !currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only managers or admins can approve team records");
        }
        YearMonth ym = parseAndValidateMonth(monthStr);
        LocalDate monthStart = ym.atDay(1);

        List<User> team = currentUser.isManager()
            ? userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id())
            : userRepository.findAll();
        List<UUID> userIds = team.stream().map(User::getId).filter(id -> !id.equals(currentUser.id())).toList();

        if (userIds.isEmpty()) return 0;

        List<PayrollRecord> records = recordRepository.findByMonthAndUserIdIn(monthStart, userIds).stream()
            .filter(r -> r.getStatus() == PayrollRecordStatus.GENERATED && r.getPaidAmount().compareTo(BigDecimal.ZERO) == 0)
            .toList();

        int approved = 0;
        for (PayrollRecord r : records) {
            try {
                approveRecord(currentUser, r.getId());
                approved++;
            } catch (Exception ignored) {}
        }
        return approved;
    }

    public PageResponse<PayrollRecordResponse> getRecords(
        CurrentUser currentUser,
        String monthStr,
        UUID departmentId,
        PayrollRecordStatus status,
        String band,
        String q,
        Pageable pageable
    ) {
        LocalDate month = (monthStr != null && !monthStr.isBlank()) ? parseAndValidateMonth(monthStr).atDay(1) : null;

        Collection<UUID> allowedUserIds = null;
        if (currentUser.isEmployee()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees must use /payroll/me");
        } else if (currentUser.isManager()) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            allowedUserIds = team.stream().map(User::getId).collect(Collectors.toSet());
            if (allowedUserIds.isEmpty()) {
                return PageResponse.of(Page.empty(pageable));
            }
        }

        if (departmentId != null) {
            List<EmployeeProfile> profs = profileRepository.findByDepartmentId(departmentId);
            Set<UUID> deptUserIds = profs.stream().map(EmployeeProfile::getUserId).collect(Collectors.toSet());
            if (allowedUserIds == null) {
                allowedUserIds = deptUserIds;
            } else {
                allowedUserIds.retainAll(deptUserIds);
            }
            if (allowedUserIds.isEmpty()) {
                return PageResponse.of(Page.empty(pageable));
            }
        }

        Page<PayrollRecord> page = recordRepository.findAll(buildSpecification(month, status, allowedUserIds, band), pageable);
        List<PayrollRecordResponse> dtos = page.getContent().stream()
            .map(r -> toResponse(r, currentUser))
            .toList();

        return new PageResponse<>(dtos, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    private org.springframework.data.jpa.domain.Specification<PayrollRecord> buildSpecification(
        LocalDate month,
        PayrollRecordStatus status,
        Collection<UUID> userIds,
        String band
    ) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (month != null) {
                predicates.add(cb.equal(root.get("month"), month));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (userIds != null && !userIds.isEmpty()) {
                predicates.add(root.get("userId").in(userIds));
            }
            if (band != null && !band.isBlank()) {
                if ("GTE70".equalsIgnoreCase(band)) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("completionPct"), new BigDecimal("70.00")));
                } else if ("B50_69".equalsIgnoreCase(band)) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("completionPct"), new BigDecimal("50.00")));
                    predicates.add(cb.lessThan(root.get("completionPct"), new BigDecimal("70.00")));
                } else if ("LT50".equalsIgnoreCase(band)) {
                    predicates.add(cb.lessThan(root.get("completionPct"), new BigDecimal("50.00")));
                }
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    public PayrollRecordResponse getRecordById(CurrentUser currentUser, UUID id) {
        PayrollRecord record = getRecordEntity(id);

        if (currentUser.isEmployee()) {
            if (!record.getUserId().equals(currentUser.id())) {
                throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Record not found");
            }
        } else if (currentUser.isManager()) {
            User emp = userRepository.findById(record.getUserId()).orElse(null);
            if (emp == null || !currentUser.id().equals(emp.getManagerId())) {
                throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Record not found");
            }
        }

        return toResponse(record, currentUser);
    }

    public PayrollSummaryResponse getSummary(CurrentUser currentUser, String monthStr) {
        YearMonth ym = parseAndValidateMonth(monthStr);
        LocalDate monthStart = ym.atDay(1);

        List<PayrollRecord> records;
        if (currentUser.isManager()) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            Set<UUID> teamIds = team.stream().map(User::getId).collect(Collectors.toSet());
            records = recordRepository.findByMonth(monthStart).stream()
                .filter(r -> teamIds.contains(r.getUserId()))
                .toList();
        } else {
            records = recordRepository.findByMonth(monthStart);
        }

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal totalReleased = BigDecimal.ZERO;
        BigDecimal totalPending = BigDecimal.ZERO;
        BigDecimal totalRetained = BigDecimal.ZERO;
        int paidCount = 0;

        for (PayrollRecord r : records) {
            totalGross = totalGross.add(r.getGross());
            totalNet = totalNet.add(r.getNetPayable());
            totalReleased = totalReleased.add(r.getPaidAmount());
            if (r.getStatus() == PayrollRecordStatus.GENERATED) {
                totalPending = totalPending.add(r.getNetPayable().subtract(r.getPaidAmount()));
            }
            totalRetained = totalRetained.add(r.getRetentionAmount());
            if (r.getStatus() == PayrollRecordStatus.RELEASED) {
                paidCount++;
            }
        }

        List<DepartmentSummaryDto> deptSummaries = new ArrayList<>();
        if (currentUser.isAdmin()) {
            List<Department> departments = departmentRepository.findAll();
            for (Department d : departments) {
                List<EmployeeProfile> profs = profileRepository.findByDepartmentId(d.getId());
                Set<UUID> uids = profs.stream().map(EmployeeProfile::getUserId).collect(Collectors.toSet());
                List<PayrollRecord> dRecs = records.stream().filter(r -> uids.contains(r.getUserId())).toList();

                BigDecimal dGross = dRecs.stream().map(PayrollRecord::getGross).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal dNet = dRecs.stream().map(PayrollRecord::getNetPayable).reduce(BigDecimal.ZERO, BigDecimal::add);

                deptSummaries.add(new DepartmentSummaryDto(d.getId(), d.getName(), dGross, dNet, dRecs.size()));
            }
        }

        return new PayrollSummaryResponse(
            ym.toString(),
            totalGross,
            totalNet,
            totalReleased,
            totalPending,
            totalRetained,
            paidCount,
            records.size(),
            deptSummaries
        );
    }

    public List<PayrollRecordResponse> getMeHistory(CurrentUser currentUser, Integer year) {
        int targetYear = (year != null) ? year : businessClock.today().getYear();
        LocalDate start = LocalDate.of(targetYear, 1, 1);
        LocalDate end = LocalDate.of(targetYear, 12, 31);

        List<PayrollRecord> records = recordRepository.findByUserIdAndYear(currentUser.id(), start, end);
        return records.stream().map(r -> toResponse(r, currentUser)).toList();
    }

    public PayrollRecordResponse getMeMonth(CurrentUser currentUser, String monthStr) {
        YearMonth ym = parseAndValidateMonth(monthStr);
        PayrollRecord record = recordRepository.findByUserIdAndMonth(currentUser.id(), ym.atDay(1))
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No payroll record found for " + monthStr));

        return toResponse(record, currentUser);
    }

    public ResponseEntity<byte[]> getPayslipPdf(CurrentUser currentUser, UUID recordId) {
        PayrollRecord record = getRecordEntity(recordId);

        // PRD D26: Managers get 403 on payslip PDF
        if (currentUser.isManager() && !record.getUserId().equals(currentUser.id())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Managers are not permitted to download employee payslip PDFs");
        }

        if (currentUser.isEmployee() || (currentUser.isManager() && record.getUserId().equals(currentUser.id()))) {
            if (!record.getUserId().equals(currentUser.id())) {
                throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Record not found");
            }
        }

        // Rule 10 & VULN-P8-5: Official payslip PDF is available only after APPROVED or RELEASED for all callers
        if (record.getStatus() != PayrollRecordStatus.APPROVED && record.getStatus() != PayrollRecordStatus.RELEASED) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_NOT_APPROVED", "Payslip PDF is only available after payroll is approved");
        }

        User user = userRepository.findById(record.getUserId()).orElse(null);
        EmployeeProfile profile = profileRepository.findById(record.getUserId()).orElse(null);
        String deptName = null;
        if (profile != null && profile.getDepartmentId() != null) {
            deptName = departmentRepository.findById(profile.getDepartmentId()).map(Department::getName).orElse(null);
        }

        BankAccountDto bank = employeeService.getBankAccount(currentUser, record.getUserId());
        List<PayrollLine> lines = lineRepository.findByRecordIdOrderByDisplayOrderAsc(record.getId());
        String compName = settingsService.getString("company.name", "Puk's");

        byte[] pdfBytes = payslipPdfService.generatePayslipPdf(
            record, user, profile, deptName, bank.accountMasked(), bank.bankName(), bank.ifsc(), lines, compName
        );

        String filename = "payslip-" + (user != null ? user.getCode() : "EMP") + "-" + record.getMonth().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")) + ".pdf";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdfBytes);
    }

    public ResponseEntity<byte[]> getMyPayslipPdf(CurrentUser currentUser, String monthStr) {
        YearMonth ym = parseAndValidateMonth(monthStr);
        PayrollRecord record = recordRepository.findByUserIdAndMonth(currentUser.id(), ym.atDay(1))
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No payroll record found for " + monthStr));
        return getPayslipPdf(currentUser, record.getId());
    }

    public void exportPayroll(
        CurrentUser currentUser,
        String format,
        String monthStr,
        UUID departmentId,
        PayrollRecordStatus status,
        HttpServletResponse response
    ) {
        if (!currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Admin can export payroll");
        }
        reportService.exportReport(
            currentUser,
            "payroll",
            format,
            null,
            null,
            monthStr,
            departmentId,
            null,
            status != null ? status.name() : null,
            response
        );
    }

    /* private void dummyOldExport() {
            if ("csv".equalsIgnoreCase(format)) {
                response.setContentType("text/csv; charset=UTF-8");
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payroll-" + (monthStr != null ? monthStr : "all") + ".csv\"");
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

                try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                    printer.printRecord("Code", "Employee", "Month", "Gross", "Completion %", "Net Payable", "Status", "Paid Amount");
                    for (PayrollRecord r : records) {
                        User u = userRepository.findById(r.getUserId()).orElse(null);
                        EmployeeProfile p = profileRepository.findById(r.getUserId()).orElse(null);
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        printer.printRecord(
                            sanitizeCsv(u != null ? u.getCode() : ""),
                            sanitizeCsv(name),
                            r.getMonth(),
                            r.getGross(),
                            r.getCompletionPct(),
                            r.getNetPayable(),
                            r.getStatus(),
                            r.getPaidAmount()
                        );
                    }
                    printer.flush();
                }
            } else if ("xlsx".equalsIgnoreCase(format)) {
                response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payroll-" + (monthStr != null ? monthStr : "all") + ".xlsx\"");
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

                try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
                    Sheet sheet = wb.createSheet("Payroll");
                    Row header = sheet.createRow(0);
                    String[] cols = {"Code", "Employee", "Month", "Gross", "Completion %", "Net Payable", "Status", "Paid Amount"};
                    for (int i = 0; i < cols.length; i++) {
                        header.createCell(i).setCellValue(cols[i]);
                    }

                    int rowIdx = 1;
                    for (PayrollRecord r : records) {
                        User u = userRepository.findById(r.getUserId()).orElse(null);
                        EmployeeProfile p = profileRepository.findById(r.getUserId()).orElse(null);
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        Row row = sheet.createRow(rowIdx++);
                        row.createCell(0).setCellValue(sanitizeCsv(u != null ? u.getCode() : ""));
                        row.createCell(1).setCellValue(sanitizeCsv(name));
                        row.createCell(2).setCellValue(r.getMonth().toString());
                        row.createCell(3).setCellValue(r.getGross().doubleValue());
                        row.createCell(4).setCellValue(r.getCompletionPct().doubleValue());
                        row.createCell(5).setCellValue(r.getNetPayable().doubleValue());
                        row.createCell(6).setCellValue(r.getStatus().name());
                        row.createCell(7).setCellValue(r.getPaidAmount().doubleValue());
                    }
                    wb.write(response.getOutputStream());
                }
            } else { // PDF
                response.setContentType(MediaType.APPLICATION_PDF_VALUE);
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payroll-" + (monthStr != null ? monthStr : "all") + ".pdf\"");
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

                Document document = new Document();
                PdfWriter.getInstance(document, response.getOutputStream());
                document.open();

                Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.BLACK);
                Paragraph title = new Paragraph("Payroll Summary Report (" + (monthStr != null ? monthStr : "All") + ")", titleFont);
                title.setAlignment(Element.ALIGN_CENTER);
                title.setSpacingAfter(15f);
                document.add(title);

                PdfPTable table = new PdfPTable(7);
                table.setWidthPercentage(100);
                table.setWidths(new float[]{15f, 25f, 15f, 15f, 15f, 15f, 15f});

                String[] cols = {"Code", "Employee", "Month", "Gross", "Net", "Status", "Paid"};
                for (String c : cols) {
                    PdfPCell cell = new PdfPCell(new Phrase(c, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9)));
                    cell.setBackgroundColor(new Color(240, 240, 240));
                    cell.setPadding(4f);
                    table.addCell(cell);
                }

                for (PayrollRecord r : records) {
                    User u = userRepository.findById(r.getUserId()).orElse(null);
                    EmployeeProfile p = profileRepository.findById(r.getUserId()).orElse(null);
                    String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                    table.addCell(new Phrase(u != null ? u.getCode() : "", FontFactory.getFont(FontFactory.HELVETICA, 8)));
                    table.addCell(new Phrase(name, FontFactory.getFont(FontFactory.HELVETICA, 8)));
                    table.addCell(new Phrase(r.getMonth().toString(), FontFactory.getFont(FontFactory.HELVETICA, 8)));
                    table.addCell(new Phrase(r.getGross().toPlainString(), FontFactory.getFont(FontFactory.HELVETICA, 8)));
                    table.addCell(new Phrase(r.getNetPayable().toPlainString(), FontFactory.getFont(FontFactory.HELVETICA, 8)));
                    table.addCell(new Phrase(r.getStatus().name(), FontFactory.getFont(FontFactory.HELVETICA, 8)));
                    table.addCell(new Phrase(r.getPaidAmount().toPlainString(), FontFactory.getFont(FontFactory.HELVETICA, 8)));
                }

                document.add(table);
                document.close();
            }
        } catch (Exception e) {
            log.error("Export failed for format {}", format, e);
            throw new RuntimeException("Export failed", e);
        }
    }

    private String sanitizeCsv(String val) {
        if (val == null) return "";
        if (val.startsWith("=") || val.startsWith("+") || val.startsWith("-") || val.startsWith("@")) {
            return "'" + val;
        }
        return val;
    } */

    private void verifySnapshotIntegrity(PayrollRecord record) {
        try {
            YearMonth ym = YearMonth.from(record.getMonth());
            AssembledPayrollInputs current = assembler.assemble(record.getUserId(), ym.toString(), false);

            JsonNode node = objectMapper.readTree(record.getSnapshot());
            BigDecimal grossSnap = new BigDecimal(node.path("gross").asText("0"));
            int tasksTotalSnap = node.path("tasksTotal").asInt(0);
            int tasksApprovedSnap = node.path("tasksApproved").asInt(0);
            BigDecimal absentDaysSnap = new BigDecimal(node.path("absentDays").asText("0"));
            BigDecimal unpaidLeaveDaysSnap = new BigDecimal(node.path("unpaidLeaveDays").asText("0"));
            BigDecimal statutorySnap = new BigDecimal(node.path("statutoryTotal").asText("0"));

            if (current.inputs().gross().compareTo(grossSnap) != 0 ||
                current.inputs().tasksTotal() != tasksTotalSnap ||
                current.inputs().tasksApproved() != tasksApprovedSnap ||
                BigDecimal.valueOf(current.inputs().absentDays()).compareTo(absentDaysSnap) != 0 ||
                BigDecimal.valueOf(current.inputs().unpaidLeaveDays()).compareTo(unpaidLeaveDaysSnap) != 0 ||
                current.inputs().statutoryTotal().compareTo(statutorySnap) != 0) {
                throw new AppException(HttpStatus.CONFLICT, "PAYROLL_STALE", "Payroll inputs have changed since generation. Please recalculate payroll before proceeding.");
            }
        } catch (AppException ae) {
            throw ae;
        } catch (Exception e) {
            // Snapshot parse fallback
        }
    }

    private void validateReleaseRequest(PayrollReleaseRequest req) {
        if (req == null || req.paymentDate() == null || req.paymentRef() == null || req.paymentRef().isBlank() || req.paymentMethod() == null || req.paymentMethod().isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Payment date, payment reference, and payment method are required");
        }
        if (req.paymentDate().isAfter(businessClock.today())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PAYROLL_FUTURE_PAYMENT_DATE", "Payment date cannot be in the future");
        }
        if (req.paymentRef().trim().length() > 100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Payment reference must not exceed 100 characters");
        }
        if (req.paymentMethod().trim().length() > 50) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Payment method must not exceed 50 characters");
        }
    }

    private PayrollRecord getRecordEntity(UUID recordId) {
        return recordRepository.findById(recordId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "PAYROLL_RECORD_NOT_FOUND", "Payroll record not found"));
    }

    private PayrollRecordResponse toResponse(PayrollRecord r, CurrentUser currentUser) {
        User u = userRepository.findById(r.getUserId()).orElse(null);
        EmployeeProfile p = profileRepository.findById(r.getUserId()).orElse(null);

        String deptName = null;
        if (p != null && p.getDepartmentId() != null) {
            deptName = departmentRepository.findById(p.getDepartmentId()).map(Department::getName).orElse(null);
        }

        String name = (p != null && p.getFirstName() != null)
            ? (p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : ""))
            : (u != null ? u.getEmail() : "");

        EmployeeBriefDto empDto = new EmployeeBriefDto(
            r.getUserId(),
            u != null ? u.getCode() : "",
            name,
            deptName != null ? deptName : "General",
            u != null ? u.getRole().name() : ""
        );

        List<PayrollLineDto> lines = lineRepository.findByRecordIdOrderByDisplayOrderAsc(r.getId()).stream()
            .map(l -> new PayrollLineDto(l.getKind().name(), l.getLabel(), l.getAmount()))
            .toList();

        // PRD D26: Manager projection omits bank details and snapshot
        BankBriefDto bankDto = null;
        String snapshot = null;

        if (currentUser.isAdmin() || (currentUser.isEmployee() && currentUser.id().equals(r.getUserId())) || (currentUser.isManager() && currentUser.id().equals(r.getUserId()))) {
            BankAccountDto b = employeeService.getBankAccount(currentUser, r.getUserId());
            bankDto = new BankBriefDto(b.bankName(), b.accountMasked(), b.ifsc());
            snapshot = r.getSnapshot();
        }

        return new PayrollRecordResponse(
            r.getId(),
            r.getVersion(),
            r.getRunId(),
            YearMonth.from(r.getMonth()).toString(),
            empDto,
            r.getGross(),
            r.getTasksTotal(),
            r.getTasksApproved(),
            r.getCompletionPct(),
            r.getRetentionDays(),
            r.getRetentionAmount(),
            r.getAbsentDays(),
            r.getAbsentDeduction(),
            r.getUnpaidLeaveDays(),
            r.getUnpaidLeaveDeduction(),
            r.getUnpaidDays(),
            r.getUnpaidDeduction(),
            r.getTdsDeduction(),
            r.getPfDeduction(),
            r.getPtDeduction(),
            r.getStatutoryTotal(),
            r.getRetentionReleasedAmount(),
            r.getNetPayable(),
            r.isNetClamped(),
            r.getPresentDays(),
            r.getWorkingDays(),
            r.getDefaultReleaseAmount(),
            r.getTaskLinkedAmount(),
            r.getTaskUnlockedAmount(),
            r.getReleasedPct(),
            r.getTranche1Amount(),
            r.getTranche2Amount(),
            r.getPaidAmount(),
            r.getTranche1PaidAt(),
            r.getTranche1PaymentRef(),
            r.getTranche1PaymentMethod(),
            r.getTranche1PaymentDate(),
            r.getPaymentDate(),
            r.getPaymentRef(),
            r.getPaymentMethod(),
            r.getStatus(),
            r.getApprovedBy(),
            r.getApprovedAt(),
            r.getReleasedBy(),
            r.getReleasedAt(),
            r.getRejectNote(),
            lines,
            bankDto,
            snapshot
        );
    }

    private RetentionResponse toRetentionResponse(PayrollRetention r, User u) {
        String name = "";
        String code = "";
        if (u != null) {
            code = u.getCode();
            EmployeeProfile p = profileRepository.findById(u.getId()).orElse(null);
            name = (p != null && p.getFirstName() != null)
                ? (p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : ""))
                : u.getEmail();
        }

        return new RetentionResponse(
            r.getId(),
            r.getRecordId(),
            r.getUserId(),
            name,
            code,
            r.getDays(),
            r.getAmount(),
            r.getStatus(),
            r.getNote(),
            r.getDecidedBy(),
            r.getDecidedAt(),
            r.getReleasedInRecordId(),
            r.getCreatedAt()
        );
    }
}
