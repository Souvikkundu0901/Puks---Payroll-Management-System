package com.octopyder.payroll.report;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.octopyder.payroll.attendance.AttendanceApprovalStatus;
import com.octopyder.payroll.attendance.AttendanceRecord;
import com.octopyder.payroll.attendance.AttendanceRecordRepository;
import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.leave.LeaveRequest;
import com.octopyder.payroll.leave.LeaveRequestRepository;
import com.octopyder.payroll.leave.LeaveStatus;
import com.octopyder.payroll.leave.LeaveType;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.payroll.PayrollRecord;
import com.octopyder.payroll.payroll.PayrollRecordRepository;
import com.octopyder.payroll.payroll.PayrollRecordStatus;
import com.octopyder.payroll.people.Department;
import com.octopyder.payroll.people.DepartmentRepository;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.task.Task;
import com.octopyder.payroll.task.TaskPriority;
import com.octopyder.payroll.task.TaskRepository;
import com.octopyder.payroll.task.TaskStatus;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final DepartmentRepository departmentRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final TaskRepository taskRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollRecordRepository payrollRecordRepository;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    private final Set<UUID> activeExports = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Bucket> adminReportBuckets = new ConcurrentHashMap<>();

    private Bucket createReportBucket() {
        Refill refill = Refill.intervally(10, Duration.ofMinutes(1));
        Bandwidth limit = Bandwidth.classic(10, refill);
        return Bucket.builder().addLimit(limit).build();
    }

    private Map<UUID, User> fetchUsers(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        return userRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    private Map<UUID, EmployeeProfile> fetchProfiles(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        return profileRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(EmployeeProfile::getUserId, p -> p, (a, b) -> a));
    }

    public ReportService(
        UserRepository userRepository,
        EmployeeProfileRepository profileRepository,
        DepartmentRepository departmentRepository,
        AttendanceRecordRepository attendanceRecordRepository,
        TaskRepository taskRepository,
        LeaveRequestRepository leaveRequestRepository,
        PayrollRecordRepository payrollRecordRepository,
        SettingsService settingsService,
        AuditService auditService,
        BusinessClock businessClock
    ) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.departmentRepository = departmentRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.taskRepository = taskRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.payrollRecordRepository = payrollRecordRepository;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.businessClock = businessClock;
    }

    @Transactional(readOnly = true)
    public void exportReport(
        CurrentUser currentUser,
        String type,
        String format,
        LocalDate from,
        LocalDate to,
        String month,
        UUID departmentId,
        UUID userId,
        String status,
        HttpServletResponse response
    ) {
        Bucket bucket = adminReportBuckets.computeIfAbsent(currentUser.id(), k -> createReportBucket());
        if (!bucket.tryConsume(1)) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many report export requests. Please wait a moment.");
        }

        if (!activeExports.add(currentUser.id())) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "EXPORT_ALREADY_IN_PROGRESS", "An export is already running for your account");
        }
        try {
            doExport(currentUser, type, format, from, to, month, departmentId, userId, status, response);
        } finally {
            activeExports.remove(currentUser.id());
        }
    }

    private void doExport(
        CurrentUser currentUser,
        String type,
        String format,
        LocalDate from,
        LocalDate to,
        String month,
        UUID departmentId,
        UUID userId,
        String status,
        HttpServletResponse response
    ) {
        String normalizedFormat = format != null ? format.trim().toLowerCase() : "csv";
        if (!List.of("csv", "xlsx", "pdf").contains(normalizedFormat)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT", "Supported formats: csv, xlsx, pdf");
        }

        String normalizedType = type != null ? type.trim().toLowerCase() : "";
        if (!List.of("employees", "attendance", "tasks", "payroll", "leave").contains(normalizedType)) {
            throw new AppException(HttpStatus.NOT_FOUND, "REPORT_TYPE_NOT_FOUND", "Unknown report type: " + type);
        }

        // Validate date range cap (366 days)
        if (("attendance".equals(normalizedType) || "leave".equals(normalizedType)) && from != null && to != null) {
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            if (days > 366) {
                throw new AppException(HttpStatus.BAD_REQUEST, "REPORT_RANGE_EXCEEDED", "Date range cannot exceed 366 days");
            }
        }

        String tz = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zoneId = ZoneId.of(tz != null ? tz : "Asia/Kolkata");

        switch (normalizedType) {
            case "employees" -> exportEmployees(currentUser, normalizedFormat, departmentId, response);
            case "attendance" -> exportAttendance(currentUser, normalizedFormat, from, to, departmentId, userId, response);
            case "tasks" -> exportTasks(currentUser, normalizedFormat, from, to, status, departmentId, userId, zoneId, response);
            case "payroll" -> exportPayroll(currentUser, normalizedFormat, month, departmentId, status, response);
            case "leave" -> exportLeave(currentUser, normalizedFormat, from, to, status, userId, response);
        }
    }

    private void exportEmployees(CurrentUser currentUser, String format, UUID departmentId, HttpServletResponse response) {
        String[] headers = {"Code", "Name", "Email", "Role", "Status", "Department", "Designation", "Joining Date"};
        int pageSize = 250;
        int page = 0;
        long totalRows = 0;

        Specification<User> spec = (root, query, cb) -> cb.isNull(root.get("deletedAt"));
        Page<User> firstPage = userRepository.findAll(spec, PageRequest.of(0, 1, Sort.by("code").ascending()));
        long totalElements = firstPage.getTotalElements();

        validatePdfLimit(format, totalElements);
        setupResponseHeaders(response, "employees", format);

        Map<UUID, String> deptMap = departmentRepository.findAll().stream()
            .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));

        if ("csv".equals(format)) {
            try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                printer.printRecord((Object[]) headers);
                while (true) {
                    Page<User> batch = userRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("code").ascending()));
                    if (batch.isEmpty()) break;
                    for (User u : batch.getContent()) {
                        EmployeeProfile p = profileRepository.findById(u.getId()).orElse(null);
                        String name = p != null && p.getFirstName() != null
                            ? p.getFirstName() + (p.getLastName() != null && !p.getLastName().isBlank() ? " " + p.getLastName() : "")
                            : "";
                        String dept = (p != null && p.getDepartmentId() != null) ? deptMap.getOrDefault(p.getDepartmentId(), "") : "";
                        String desig = (p != null && p.getDesignation() != null) ? p.getDesignation() : "";
                        String jDate = (p != null && p.getJoiningDate() != null) ? p.getJoiningDate().toString() : "";

                        printer.printRecord(
                            sanitize(u.getCode()),
                            sanitize(name),
                            sanitize(u.getEmail()),
                            u.getRole().name(),
                            u.getStatus().name(),
                            sanitize(dept),
                            sanitize(desig),
                            jDate
                        );
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to export employees CSV", e);
            }
        } else if ("xlsx".equals(format)) {
            try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
                Sheet sheet = wb.createSheet("Employees");
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.length; i++) headerRow.createCell(i).setCellValue(headers[i]);

                int rowIdx = 1;
                while (true) {
                    Page<User> batch = userRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("code").ascending()));
                    if (batch.isEmpty()) break;
                    for (User u : batch.getContent()) {
                        EmployeeProfile p = profileRepository.findById(u.getId()).orElse(null);
                        String name = p != null && p.getFirstName() != null
                            ? p.getFirstName() + (p.getLastName() != null && !p.getLastName().isBlank() ? " " + p.getLastName() : "")
                            : "";
                        String dept = (p != null && p.getDepartmentId() != null) ? deptMap.getOrDefault(p.getDepartmentId(), "") : "";
                        String desig = (p != null && p.getDesignation() != null) ? p.getDesignation() : "";
                        String jDate = (p != null && p.getJoiningDate() != null) ? p.getJoiningDate().toString() : "";

                        Row r = sheet.createRow(rowIdx++);
                        r.createCell(0).setCellValue(sanitize(u.getCode()));
                        r.createCell(1).setCellValue(sanitize(name));
                        r.createCell(2).setCellValue(sanitize(u.getEmail()));
                        r.createCell(3).setCellValue(u.getRole().name());
                        r.createCell(4).setCellValue(u.getStatus().name());
                        r.createCell(5).setCellValue(sanitize(dept));
                        r.createCell(6).setCellValue(sanitize(desig));
                        r.createCell(7).setCellValue(jDate);
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
                wb.write(response.getOutputStream());
            } catch (Exception e) {
                throw new RuntimeException("Failed to export employees XLSX", e);
            }
        } else { // PDF
            renderPdfTable(response, "Employees Report", headers, new float[]{15f, 20f, 25f, 12f, 12f, 16f}, table -> {
                long count = 0;
                int pIdx = 0;
                while (true) {
                    Page<User> batch = userRepository.findAll(spec, PageRequest.of(pIdx, pageSize, Sort.by("code").ascending()));
                    if (batch.isEmpty()) break;
                    for (User u : batch.getContent()) {
                        EmployeeProfile p = profileRepository.findById(u.getId()).orElse(null);
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";
                        String dept = (p != null && p.getDepartmentId() != null) ? deptMap.getOrDefault(p.getDepartmentId(), "") : "";
                        table.addCell(createPdfCell(u.getCode()));
                        table.addCell(createPdfCell(name));
                        table.addCell(createPdfCell(u.getEmail()));
                        table.addCell(createPdfCell(u.getRole().name()));
                        table.addCell(createPdfCell(u.getStatus().name()));
                        table.addCell(createPdfCell(dept));
                        count++;
                    }
                    if (batch.isLast()) break;
                    pIdx++;
                }
                return count;
            });
            totalRows = totalElements;
        }

        auditService.log("REPORT_EXPORT", "REPORT", "EMPLOYEES", null, "Format: " + format + ", rowCount: " + totalRows);
    }

    private void exportAttendance(CurrentUser currentUser, String format, LocalDate from, LocalDate to, UUID departmentId, UUID userId, HttpServletResponse response) {
        String[] headers = {"Date", "Code", "Employee", "Mode", "Day Type", "Status", "Late", "Worked Minutes"};
        int pageSize = 250;
        int page = 0;
        long totalRows = 0;

        Specification<AttendanceRecord> spec = (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (from != null) predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("workDate"), from));
            if (to != null) predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("workDate"), to));
            if (userId != null) predicates = cb.and(predicates, cb.equal(root.get("userId"), userId));
            return predicates;
        };

        long totalElements = attendanceRecordRepository.count(spec);
        validatePdfLimit(format, totalElements);
        setupResponseHeaders(response, "attendance", format);

        if ("csv".equals(format)) {
            try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                printer.printRecord((Object[]) headers);
                while (true) {
                    Page<AttendanceRecord> batch = attendanceRecordRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("workDate").descending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(AttendanceRecord::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (AttendanceRecord r : batch.getContent()) {
                        User u = userMap.get(r.getUserId());
                        EmployeeProfile p = profileMap.get(r.getUserId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        printer.printRecord(
                            r.getWorkDate().toString(),
                            sanitize(u != null ? u.getCode() : ""),
                            sanitize(name),
                            r.getWorkMode() != null ? r.getWorkMode().name() : "",
                            r.getDayType() != null ? r.getDayType().name() : "",
                            r.getApprovalStatus() != null ? r.getApprovalStatus().name() : "",
                            r.isLate() ? "YES" : "NO",
                            r.getWorkedMinutes()
                        );
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to export attendance CSV", e);
            }
        } else if ("xlsx".equals(format)) {
            try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
                Sheet sheet = wb.createSheet("Attendance");
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.length; i++) headerRow.createCell(i).setCellValue(headers[i]);

                int rowIdx = 1;
                while (true) {
                    Page<AttendanceRecord> batch = attendanceRecordRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("workDate").descending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(AttendanceRecord::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (AttendanceRecord r : batch.getContent()) {
                        User u = userMap.get(r.getUserId());
                        EmployeeProfile p = profileMap.get(r.getUserId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        Row row = sheet.createRow(rowIdx++);
                        row.createCell(0).setCellValue(r.getWorkDate().toString());
                        row.createCell(1).setCellValue(sanitize(u != null ? u.getCode() : ""));
                        row.createCell(2).setCellValue(sanitize(name));
                        row.createCell(3).setCellValue(r.getWorkMode() != null ? r.getWorkMode().name() : "");
                        row.createCell(4).setCellValue(r.getDayType() != null ? r.getDayType().name() : "");
                        row.createCell(5).setCellValue(r.getApprovalStatus() != null ? r.getApprovalStatus().name() : "");
                        row.createCell(6).setCellValue(r.isLate() ? "YES" : "NO");
                        row.createCell(7).setCellValue(r.getWorkedMinutes());
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
                wb.write(response.getOutputStream());
            } catch (Exception e) {
                throw new RuntimeException("Failed to export attendance XLSX", e);
            }
        } else { // PDF
            renderPdfTable(response, "Attendance Report", headers, new float[]{15f, 15f, 20f, 15f, 15f, 15f, 10f, 15f}, table -> {
                long count = 0;
                int pIdx = 0;
                while (true) {
                    Page<AttendanceRecord> batch = attendanceRecordRepository.findAll(spec, PageRequest.of(pIdx, pageSize, Sort.by("workDate").descending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(AttendanceRecord::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (AttendanceRecord r : batch.getContent()) {
                        User u = userMap.get(r.getUserId());
                        EmployeeProfile p = profileMap.get(r.getUserId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        table.addCell(createPdfCell(r.getWorkDate().toString()));
                        table.addCell(createPdfCell(u != null ? u.getCode() : ""));
                        table.addCell(createPdfCell(name));
                        table.addCell(createPdfCell(r.getWorkMode() != null ? r.getWorkMode().name() : ""));
                        table.addCell(createPdfCell(r.getDayType() != null ? r.getDayType().name() : ""));
                        table.addCell(createPdfCell(r.getApprovalStatus() != null ? r.getApprovalStatus().name() : ""));
                        table.addCell(createPdfCell(r.isLate() ? "YES" : "NO"));
                        table.addCell(createPdfCell(String.valueOf(r.getWorkedMinutes())));
                        count++;
                    }
                    if (batch.isLast()) break;
                    pIdx++;
                }
                return count;
            });
            totalRows = totalElements;
        }

        auditService.log("REPORT_EXPORT", "REPORT", "ATTENDANCE", null, "Format: " + format + ", rowCount: " + totalRows);
    }

    private void exportTasks(CurrentUser currentUser, String format, LocalDate from, LocalDate to, String statusStr, UUID departmentId, UUID userId, ZoneId zoneId, HttpServletResponse response) {
        String[] headers = {"Code", "Title", "Assignee Code", "Assignee Name", "Priority", "Status", "Progress %", "Deadline"};
        int pageSize = 250;
        int page = 0;
        long totalRows = 0;

        Specification<Task> spec = (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (from != null) predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("deadlineAt"), from.atStartOfDay(zoneId).toInstant()));
            if (to != null) predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("deadlineAt"), to.plusDays(1).atStartOfDay(zoneId).toInstant()));
            if (userId != null) predicates = cb.and(predicates, cb.equal(root.get("assigneeId"), userId));
            if (departmentId != null) predicates = cb.and(predicates, cb.equal(root.get("departmentId"), departmentId));
            if (statusStr != null && !statusStr.isBlank()) {
                try {
                    TaskStatus st = TaskStatus.valueOf(statusStr.toUpperCase());
                    predicates = cb.and(predicates, cb.equal(root.get("status"), st));
                } catch (IllegalArgumentException ignored) {}
            }
            return predicates;
        };

        long totalElements = taskRepository.count(spec);
        validatePdfLimit(format, totalElements);
        setupResponseHeaders(response, "tasks", format);

        if ("csv".equals(format)) {
            try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                printer.printRecord((Object[]) headers);
                while (true) {
                    Page<Task> batch = taskRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("deadlineAt").ascending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(Task::getAssigneeId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (Task t : batch.getContent()) {
                        User u = userMap.get(t.getAssigneeId());
                        EmployeeProfile p = profileMap.get(t.getAssigneeId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";
                        String deadline = t.getDeadlineAt() != null ? t.getDeadlineAt().atZone(zoneId).toLocalDate().toString() : "";

                        printer.printRecord(
                            sanitize(t.getCode()),
                            sanitize(t.getTitle()),
                            sanitize(u != null ? u.getCode() : ""),
                            sanitize(name),
                            t.getPriority().name(),
                            t.getStatus().name(),
                            t.getProgress(),
                            deadline
                        );
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to export tasks CSV", e);
            }
        } else if ("xlsx".equals(format)) {
            try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
                Sheet sheet = wb.createSheet("Tasks");
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.length; i++) headerRow.createCell(i).setCellValue(headers[i]);

                int rowIdx = 1;
                while (true) {
                    Page<Task> batch = taskRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("deadlineAt").ascending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(Task::getAssigneeId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (Task t : batch.getContent()) {
                        User u = userMap.get(t.getAssigneeId());
                        EmployeeProfile p = profileMap.get(t.getAssigneeId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";
                        String deadline = t.getDeadlineAt() != null ? t.getDeadlineAt().atZone(zoneId).toLocalDate().toString() : "";

                        Row row = sheet.createRow(rowIdx++);
                        row.createCell(0).setCellValue(sanitize(t.getCode()));
                        row.createCell(1).setCellValue(sanitize(t.getTitle()));
                        row.createCell(2).setCellValue(sanitize(u != null ? u.getCode() : ""));
                        row.createCell(3).setCellValue(sanitize(name));
                        row.createCell(4).setCellValue(t.getPriority().name());
                        row.createCell(5).setCellValue(t.getStatus().name());
                        row.createCell(6).setCellValue(t.getProgress());
                        row.createCell(7).setCellValue(deadline);
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
                wb.write(response.getOutputStream());
            } catch (Exception e) {
                throw new RuntimeException("Failed to export tasks XLSX", e);
            }
        } else { // PDF
            renderPdfTable(response, "Tasks Report", headers, new float[]{15f, 25f, 15f, 20f, 12f, 15f, 12f, 15f}, table -> {
                long count = 0;
                int pIdx = 0;
                while (true) {
                    Page<Task> batch = taskRepository.findAll(spec, PageRequest.of(pIdx, pageSize, Sort.by("deadlineAt").ascending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(Task::getAssigneeId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (Task t : batch.getContent()) {
                        User u = userMap.get(t.getAssigneeId());
                        EmployeeProfile p = profileMap.get(t.getAssigneeId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";
                        String deadline = t.getDeadlineAt() != null ? t.getDeadlineAt().atZone(zoneId).toLocalDate().toString() : "";

                        table.addCell(createPdfCell(t.getCode()));
                        table.addCell(createPdfCell(t.getTitle()));
                        table.addCell(createPdfCell(u != null ? u.getCode() : ""));
                        table.addCell(createPdfCell(name));
                        table.addCell(createPdfCell(t.getPriority().name()));
                        table.addCell(createPdfCell(t.getStatus().name()));
                        table.addCell(createPdfCell(t.getProgress() + "%"));
                        table.addCell(createPdfCell(deadline));
                        count++;
                    }
                    if (batch.isLast()) break;
                    pIdx++;
                }
                return count;
            });
            totalRows = totalElements;
        }

        auditService.log("REPORT_EXPORT", "REPORT", "TASKS", null, "Format: " + format + ", rowCount: " + totalRows);
    }

    private void exportPayroll(CurrentUser currentUser, String format, String monthStr, UUID departmentId, String statusStr, HttpServletResponse response) {
        String[] headers = {"Code", "Employee", "Month", "Gross", "Completion %", "Net Payable", "Status", "Paid Amount"};
        int pageSize = 250;
        int page = 0;
        long totalRows = 0;

        LocalDate month = (monthStr != null && !monthStr.isBlank()) ? LocalDate.parse(monthStr + "-01") : null;
        PayrollRecordStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try { status = PayrollRecordStatus.valueOf(statusStr.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }

        final LocalDate finalMonth = month;
        final PayrollRecordStatus finalStatus = status;

        Specification<PayrollRecord> spec = (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (finalMonth != null) predicates = cb.and(predicates, cb.equal(root.get("month"), finalMonth));
            if (finalStatus != null) predicates = cb.and(predicates, cb.equal(root.get("status"), finalStatus));
            return predicates;
        };

        long totalElements = payrollRecordRepository.count(spec);
        validatePdfLimit(format, totalElements);
        setupResponseHeaders(response, "payroll", format);

        if ("csv".equals(format)) {
            try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                printer.printRecord((Object[]) headers);
                while (true) {
                    Page<PayrollRecord> batch = payrollRecordRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("month").descending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(PayrollRecord::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (PayrollRecord r : batch.getContent()) {
                        User u = userMap.get(r.getUserId());
                        EmployeeProfile p = profileMap.get(r.getUserId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        printer.printRecord(
                            sanitize(u != null ? u.getCode() : ""),
                            sanitize(name),
                            r.getMonth().toString(),
                            r.getGross(),
                            r.getCompletionPct(),
                            r.getNetPayable(),
                            r.getStatus().name(),
                            r.getPaidAmount()
                        );
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to export payroll CSV", e);
            }
        } else if ("xlsx".equals(format)) {
            try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
                Sheet sheet = wb.createSheet("Payroll");
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.length; i++) headerRow.createCell(i).setCellValue(headers[i]);

                int rowIdx = 1;
                while (true) {
                    Page<PayrollRecord> batch = payrollRecordRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("month").descending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(PayrollRecord::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (PayrollRecord r : batch.getContent()) {
                        User u = userMap.get(r.getUserId());
                        EmployeeProfile p = profileMap.get(r.getUserId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        Row row = sheet.createRow(rowIdx++);
                        row.createCell(0).setCellValue(sanitize(u != null ? u.getCode() : ""));
                        row.createCell(1).setCellValue(sanitize(name));
                        row.createCell(2).setCellValue(r.getMonth().toString());
                        row.createCell(3).setCellValue(r.getGross() != null ? r.getGross().doubleValue() : 0.0);
                        row.createCell(4).setCellValue(r.getCompletionPct() != null ? r.getCompletionPct().doubleValue() : 0.0);
                        row.createCell(5).setCellValue(r.getNetPayable() != null ? r.getNetPayable().doubleValue() : 0.0);
                        row.createCell(6).setCellValue(r.getStatus().name());
                        row.createCell(7).setCellValue(r.getPaidAmount() != null ? r.getPaidAmount().doubleValue() : 0.0);
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
                wb.write(response.getOutputStream());
            } catch (Exception e) {
                throw new RuntimeException("Failed to export payroll XLSX", e);
            }
        } else { // PDF
            renderPdfTable(response, "Payroll Report", headers, new float[]{15f, 20f, 15f, 15f, 15f, 15f, 15f, 15f}, table -> {
                long count = 0;
                int pIdx = 0;
                while (true) {
                    Page<PayrollRecord> batch = payrollRecordRepository.findAll(spec, PageRequest.of(pIdx, pageSize, Sort.by("month").descending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(PayrollRecord::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (PayrollRecord r : batch.getContent()) {
                        User u = userMap.get(r.getUserId());
                        EmployeeProfile p = profileMap.get(r.getUserId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        table.addCell(createPdfCell(u != null ? u.getCode() : ""));
                        table.addCell(createPdfCell(name));
                        table.addCell(createPdfCell(r.getMonth().toString()));
                        table.addCell(createPdfCell(r.getGross() != null ? r.getGross().toString() : "0.00"));
                        table.addCell(createPdfCell(r.getCompletionPct() != null ? r.getCompletionPct().toString() + "%" : "0%"));
                        table.addCell(createPdfCell(r.getNetPayable() != null ? r.getNetPayable().toString() : "0.00"));
                        table.addCell(createPdfCell(r.getStatus().name()));
                        table.addCell(createPdfCell(r.getPaidAmount() != null ? r.getPaidAmount().toString() : "0.00"));
                        count++;
                    }
                    if (batch.isLast()) break;
                    pIdx++;
                }
                return count;
            });
            totalRows = totalElements;
        }

        auditService.log("REPORT_EXPORT", "REPORT", "PAYROLL", null, "Format: " + format + ", rowCount: " + totalRows);
    }

    private void exportLeave(CurrentUser currentUser, String format, LocalDate from, LocalDate to, String statusStr, UUID userId, HttpServletResponse response) {
        String[] headers = {"Employee Code", "Employee Name", "Type", "Start Date", "End Date", "Days", "Status"};
        int pageSize = 250;
        int page = 0;
        long totalRows = 0;

        Specification<LeaveRequest> spec = (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (from != null) predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("startDate"), from));
            if (to != null) predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("endDate"), to));
            if (userId != null) predicates = cb.and(predicates, cb.equal(root.get("userId"), userId));
            if (statusStr != null && !statusStr.isBlank()) {
                try {
                    LeaveStatus st = LeaveStatus.valueOf(statusStr.toUpperCase());
                    predicates = cb.and(predicates, cb.equal(root.get("status"), st));
                } catch (IllegalArgumentException ignored) {}
            }
            return predicates;
        };

        long totalElements = leaveRequestRepository.count(spec);
        validatePdfLimit(format, totalElements);
        setupResponseHeaders(response, "leave", format);

        if ("csv".equals(format)) {
            try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                printer.printRecord((Object[]) headers);
                while (true) {
                    Page<LeaveRequest> batch = leaveRequestRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("startDate").descending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(LeaveRequest::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (LeaveRequest l : batch.getContent()) {
                        User u = userMap.get(l.getUserId());
                        EmployeeProfile p = profileMap.get(l.getUserId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        printer.printRecord(
                            sanitize(u != null ? u.getCode() : ""),
                            sanitize(name),
                            l.getLeaveType().name(),
                            l.getStartDate().toString(),
                            l.getEndDate().toString(),
                            l.getDays(),
                            l.getStatus().name()
                        );
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to export leave CSV", e);
            }
        } else if ("xlsx".equals(format)) {
            try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
                Sheet sheet = wb.createSheet("Leaves");
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.length; i++) headerRow.createCell(i).setCellValue(headers[i]);

                int rowIdx = 1;
                while (true) {
                    Page<LeaveRequest> batch = leaveRequestRepository.findAll(spec, PageRequest.of(page, pageSize, Sort.by("startDate").descending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(LeaveRequest::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (LeaveRequest l : batch.getContent()) {
                        User u = userMap.get(l.getUserId());
                        EmployeeProfile p = profileMap.get(l.getUserId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        Row row = sheet.createRow(rowIdx++);
                        row.createCell(0).setCellValue(sanitize(u != null ? u.getCode() : ""));
                        row.createCell(1).setCellValue(sanitize(name));
                        row.createCell(2).setCellValue(l.getLeaveType().name());
                        row.createCell(3).setCellValue(l.getStartDate().toString());
                        row.createCell(4).setCellValue(l.getEndDate().toString());
                        row.createCell(5).setCellValue(l.getDays() != null ? l.getDays().doubleValue() : 0.0);
                        row.createCell(6).setCellValue(l.getStatus().name());
                        totalRows++;
                    }
                    if (batch.isLast()) break;
                    page++;
                }
                wb.write(response.getOutputStream());
            } catch (Exception e) {
                throw new RuntimeException("Failed to export leave XLSX", e);
            }
        } else { // PDF
            renderPdfTable(response, "Leave Report", headers, new float[]{15f, 20f, 15f, 15f, 15f, 10f, 15f}, table -> {
                long count = 0;
                int pIdx = 0;
                while (true) {
                    Page<LeaveRequest> batch = leaveRequestRepository.findAll(spec, PageRequest.of(pIdx, pageSize, Sort.by("startDate").descending()));
                    if (batch.isEmpty()) break;
                    Set<UUID> batchUserIds = batch.getContent().stream().map(LeaveRequest::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, User> userMap = fetchUsers(batchUserIds);
                    Map<UUID, EmployeeProfile> profileMap = fetchProfiles(batchUserIds);
                    for (LeaveRequest l : batch.getContent()) {
                        User u = userMap.get(l.getUserId());
                        EmployeeProfile p = profileMap.get(l.getUserId());
                        String name = p != null && p.getFirstName() != null ? p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : "") : "";

                        table.addCell(createPdfCell(u != null ? u.getCode() : ""));
                        table.addCell(createPdfCell(name));
                        table.addCell(createPdfCell(l.getLeaveType().name()));
                        table.addCell(createPdfCell(l.getStartDate().toString()));
                        table.addCell(createPdfCell(l.getEndDate().toString()));
                        table.addCell(createPdfCell(l.getDays() != null ? l.getDays().toString() : "0"));
                        table.addCell(createPdfCell(l.getStatus().name()));
                        count++;
                    }
                    if (batch.isLast()) break;
                    pIdx++;
                }
                return count;
            });
            totalRows = totalElements;
        }

        auditService.log("REPORT_EXPORT", "REPORT", "LEAVE", null, "Format: " + format + ", rowCount: " + totalRows);
    }

    private void validatePdfLimit(String format, long totalElements) {
        if ("pdf".equals(format) && totalElements > 1000) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "REPORT_PDF_ROW_LIMIT", "PDF export exceeds maximum limit of 1000 rows. Please use CSV or XLSX.");
        }
    }

    private void setupResponseHeaders(HttpServletResponse response, String type, String format) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String filename = "report-" + type + "-" + businessClock.today() + "." + format;
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        if ("csv".equals(format)) {
            response.setContentType("text/csv; charset=UTF-8");
        } else if ("xlsx".equals(format)) {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } else {
            response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        }
    }

    private void renderPdfTable(HttpServletResponse response, String titleText, String[] headers, float[] columnWidths, java.util.function.Function<PdfPTable, Long> tablePopulator) {
        try {
            Document document = new Document();
            PdfWriter.getInstance(document, response.getOutputStream());
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.BLACK);
            Paragraph title = new Paragraph(titleText, titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(15f);
            document.add(title);

            PdfPTable table = new PdfPTable(headers.length);
            table.setWidthPercentage(100);
            if (columnWidths != null && columnWidths.length == headers.length) {
                table.setWidths(columnWidths);
            }

            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9)));
                cell.setBackgroundColor(new Color(240, 240, 240));
                cell.setPadding(4f);
                table.addCell(cell);
            }

            tablePopulator.apply(table);
            document.add(table);
            document.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF report", e);
        }
    }

    private PdfPCell createPdfCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", FontFactory.getFont(FontFactory.HELVETICA, 8)));
        cell.setPadding(4f);
        return cell;
    }

    public static String sanitize(String val) {
        if (val == null) return "";
        String s = val.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) {
            return "'" + s;
        }
        return s;
    }
}
