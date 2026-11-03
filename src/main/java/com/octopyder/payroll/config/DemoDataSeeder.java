package com.octopyder.payroll.config;

import com.octopyder.payroll.attendance.AttendanceApprovalStatus;
import com.octopyder.payroll.attendance.AttendanceDayType;
import com.octopyder.payroll.attendance.AttendanceRecord;
import com.octopyder.payroll.attendance.AttendanceRecordRepository;
import com.octopyder.payroll.attendance.AttendanceWorkMode;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.org.OfficeLocation;
import com.octopyder.payroll.org.OfficeLocationRepository;
import com.octopyder.payroll.payroll.dto.PayrollReleaseAllRequest;
import com.octopyder.payroll.payroll.dto.PayrollRunRequest;
import com.octopyder.payroll.payroll.dto.PayrollRunResponse;
import com.octopyder.payroll.payroll.service.PayrollWorkflowService;
import com.octopyder.payroll.people.Department;
import com.octopyder.payroll.people.DepartmentRepository;
import com.octopyder.payroll.people.EmployeeService;
import com.octopyder.payroll.people.ManagerService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.dto.AddEmployeeRequest;
import com.octopyder.payroll.people.dto.AddManagerRequest;
import com.octopyder.payroll.people.dto.AssignTeamRequest;
import com.octopyder.payroll.people.dto.CreateEmployeeResponse;
import com.octopyder.payroll.task.TaskPriority;
import com.octopyder.payroll.task.TaskRepository;
import com.octopyder.payroll.task.TaskService;
import com.octopyder.payroll.task.dto.CreateTaskRequest;
import com.octopyder.payroll.task.dto.TaskDto;
import com.octopyder.payroll.task.dto.TaskProgressRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!prod & !test")
@ConditionalOnProperty(name = "app.seed-demo-data", havingValue = "true", matchIfMissing = false)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final OfficeLocationRepository officeLocationRepository;
    private final EmployeeService employeeService;
    private final ManagerService managerService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final PayrollWorkflowService payrollWorkflowService;
    private final BusinessClock businessClock;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public DemoDataSeeder(
        UserRepository userRepository,
        DepartmentRepository departmentRepository,
        OfficeLocationRepository officeLocationRepository,
        EmployeeService employeeService,
        ManagerService managerService,
        TaskService taskService,
        TaskRepository taskRepository,
        AttendanceRecordRepository attendanceRecordRepository,
        PayrollWorkflowService payrollWorkflowService,
        BusinessClock businessClock,
        JdbcTemplate jdbcTemplate
    ) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.officeLocationRepository = officeLocationRepository;
        this.employeeService = employeeService;
        this.managerService = managerService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.payrollWorkflowService = payrollWorkflowService;
        this.businessClock = businessClock;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.findByEmailIgnoreCase("manager@octopyder.com").isPresent()) {
            log.info("Demo data already seeded, skipping seeder.");
            return;
        }

        log.info("Starting demo data seeding in a single transaction...");

        // Ensure user code sequences are aligned with max existing codes in database
        jdbcTemplate.execute("SELECT setval('seq_emp_code', GREATEST(COALESCE((SELECT MAX(SUBSTRING(code FROM 4)::integer) FROM users WHERE code ~ '^EMP[0-9]+$'), 0), 1), true)");
        jdbcTemplate.execute("SELECT setval('seq_mgr_code', GREATEST(COALESCE((SELECT MAX(SUBSTRING(code FROM 4)::integer) FROM users WHERE code ~ '^MGR[0-9]+$'), 0), 1), true)");
        jdbcTemplate.execute("SELECT setval('seq_adm_code', GREATEST(COALESCE((SELECT MAX(SUBSTRING(code FROM 4)::integer) FROM users WHERE code ~ '^ADM[0-9]+$'), 0), 1), true)");

        // 1. Departments
        Department engDept = departmentRepository.findByNameIgnoreCase("Engineering").orElseGet(() -> {
            Department d = new Department("ENG", "Engineering");
            return departmentRepository.save(d);
        });

        // 2. Office location
        if (officeLocationRepository.count() == 0) {
            OfficeLocation hq = new OfficeLocation(
                "Headquarters",
                new BigDecimal("12.9716000"),
                new BigDecimal("77.5946000"),
                500,
                List.of("127.0.0.1/32", "192.168.1.0/24")
            );
            officeLocationRepository.save(hq);
        }

        // 3. Manager
        AddManagerRequest mgrReq = new AddManagerRequest(
            "Vikram",
            "Seth",
            "manager@octopyder.com",
            "+91 98765 43210",
            engDept.getId(),
            "Engineering Manager",
            new BigDecimal("12.0"),
            new BigDecimal("120000.00")
        );
        CreateEmployeeResponse mgrResp = managerService.createManager(mgrReq);
        UUID managerId = mgrResp.id();

        // 4. Employees across 3 retention bands:
        // Band 1 (0-day retention): 2 employees (>= 90% task completion)
        // Band 2 (2-day retention): 3 employees (75% - 89% task completion)
        // Band 3 (7-day retention): 2 employees (< 75% task completion)
        record EmpSeed(String first, String last, String email, String desig, BigDecimal salary, int band) {}
        List<EmpSeed> seeds = List.of(
            new EmpSeed("Ananya", "Sharma", "ananya.sharma@octopyder.com", "Senior Backend Engineer", new BigDecimal("75000.00"), 0),
            new EmpSeed("Rohan", "Verma", "rohan.verma@octopyder.com", "Full Stack Developer", new BigDecimal("65000.00"), 0),
            new EmpSeed("Priya", "Patel", "priya.patel@octopyder.com", "Frontend Engineer", new BigDecimal("55000.00"), 2),
            new EmpSeed("Amit", "Kumar", "amit.kumar@octopyder.com", "DevOps Engineer", new BigDecimal("58000.00"), 2),
            new EmpSeed("Neha", "Gupta", "neha.gupta@octopyder.com", "QA Engineer", new BigDecimal("52000.00"), 2),
            new EmpSeed("Suresh", "Raina", "suresh.raina@octopyder.com", "Junior Developer", new BigDecimal("45000.00"), 7),
            new EmpSeed("Kavita", "Nair", "kavita.nair@octopyder.com", "UI/UX Designer", new BigDecimal("48000.00"), 7)
        );

        LocalDate joinDate = businessClock.today().minusMonths(6);
        List<UUID> empUserIds = new ArrayList<>();
        List<User> empUsers = new ArrayList<>();

        for (EmpSeed seed : seeds) {
            AddEmployeeRequest req = new AddEmployeeRequest(
                seed.first(),
                seed.last(),
                seed.email(),
                "+91 98765 11111",
                "Indiranagar, Bengaluru",
                "OTHER",
                LocalDate.of(1995, 8, 20),
                "B+",
                "+91 98765 22222",
                "Single",
                engDept.getId(),
                seed.desig(),
                "FULL_TIME",
                joinDate,
                managerId,
                seed.salary(),
                new BigDecimal("4.0"),
                new String[]{"Java", "Spring", "SQL"},
                Role.EMPLOYEE,
                "Employee123!"
            );
            CreateEmployeeResponse created = employeeService.createEmployee(req, null, false);
            empUserIds.add(created.id());
            empUsers.add(userRepository.findById(created.id()).orElseThrow());
        }

        // Assign team to manager
        managerService.assignTeam(managerId, new AssignTeamRequest(empUserIds));

        // Ensure known credentials and mustChangePassword=false for demo users
        User mgrUser = userRepository.findById(managerId).orElseThrow();
        mgrUser.setPasswordHash(passwordEncoder.encode("Manager123!"));
        mgrUser.setMustChangePassword(false);
        userRepository.save(mgrUser);

        for (User emp : empUsers) {
            emp.setPasswordHash(passwordEncoder.encode("Employee123!"));
            emp.setMustChangePassword(false);
            userRepository.save(emp);
        }

        userRepository.findByEmailIgnoreCase("admin@octopyder.com").ifPresent(adm -> {
            adm.setPasswordHash(passwordEncoder.encode("AdminBootstrap123!"));
            adm.setMustChangePassword(false);
            userRepository.save(adm);
        });

        // Relative past month for attendance, tasks, and completed payroll
        LocalDate today = businessClock.today();
        YearMonth pastYm = YearMonth.from(today.minusMonths(1));
        String pastMonthStr = pastYm.toString();
        LocalDate monthStart = pastYm.atDay(1);
        LocalDate monthEnd = pastYm.atEndOfMonth();

        // 5. Attendance for past month
        for (LocalDate date = monthStart; !date.isAfter(monthEnd); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            for (User emp : empUsers) {
                AttendanceRecord att = new AttendanceRecord();
                att.setUserId(emp.getId());
                att.setWorkDate(date);
                att.setCheckInAt(date.atTime(9, 15).toInstant(ZoneOffset.UTC));
                att.setCheckOutAt(date.atTime(18, 15).toInstant(ZoneOffset.UTC));
                att.setWorkedMinutes(540);
                att.setOvertimeMinutes(0);
                att.setWorkMode(AttendanceWorkMode.OFFICE);
                att.setDayType(AttendanceDayType.PRESENT);
                att.setLate(false);
                att.setApprovalStatus(AttendanceApprovalStatus.APPROVED);
                att.setApprovedBy(managerId);
                att.setApprovedAt(businessClock.instant());
                attendanceRecordRepository.save(att);
            }
        }

        // 6. Tasks for past month
        CurrentUser managerUser = new CurrentUser(managerId, "MGR001", "manager@octopyder.com", Role.MANAGER, 1L);
        Instant deadline = pastYm.atDay(20).atTime(18, 0).toInstant(ZoneOffset.UTC);

        for (int i = 0; i < seeds.size(); i++) {
            EmpSeed seed = seeds.get(i);
            User emp = empUsers.get(i);
            CurrentUser empUser = new CurrentUser(emp.getId(), emp.getCode(), emp.getEmail(), Role.EMPLOYEE, 1L);

            int targetBand = seed.band();
            // 10 tasks assigned each
            // Band 0: 10/10 approved (100%)
            // Band 2: 8/10 approved (80%)
            // Band 7: 5/10 approved (50%)
            int approvedCount = targetBand == 0 ? 10 : (targetBand == 2 ? 8 : 5);

            for (int t = 1; t <= 10; t++) {
                CreateTaskRequest taskReq = new CreateTaskRequest(
                    "Task " + t + " for " + seed.first(),
                    "Complete sprint backlog deliverable " + t,
                    TaskPriority.MEDIUM,
                    emp.getId(),
                    managerId,
                    engDept.getId(),
                    deadline,
                    new BigDecimal("8.0")
                );
                TaskDto createdTask = taskService.createTask(managerUser, taskReq);

                if (t <= approvedCount) {
                    taskService.startTask(empUser, createdTask.id());
                    taskService.updateProgress(empUser, createdTask.id(), new TaskProgressRequest(100));
                    taskService.approveTask(managerUser, createdTask.id());
                } else {
                    taskService.startTask(empUser, createdTask.id());
                    taskService.updateProgress(empUser, createdTask.id(), new TaskProgressRequest(50));
                }
            }
        }

        // 7. Completed past payroll month
        User admin = userRepository.findByEmailIgnoreCase("admin@octopyder.com").orElse(null);
        if (admin != null) {
            CurrentUser adminUser = new CurrentUser(admin.getId(), admin.getCode(), admin.getEmail(), Role.ADMIN, 1L);
            try {
                // Generate run
                PayrollRunResponse run = payrollWorkflowService.generateRun(
                    adminUser,
                    new PayrollRunRequest(pastMonthStr, engDept.getId(), true, false)
                );

                // Manager bulk approves records
                payrollWorkflowService.bulkApproveTeamRecords(managerUser, pastMonthStr);

                // Admin releases tranche 1 (default 50%) and tranche 2 (remainder)
                payrollWorkflowService.releaseAll(
                    adminUser,
                    new PayrollReleaseAllRequest(run.runId(), pastMonthStr, today, "DEMO-TXN-DEF", "BANK_TRANSFER")
                );
                payrollWorkflowService.releaseAll(
                    adminUser,
                    new PayrollReleaseAllRequest(run.runId(), pastMonthStr, today, "DEMO-TXN-REM", "BANK_TRANSFER")
                );

                log.info("Demo past payroll month {} fully generated, approved and released.", pastMonthStr);
            } catch (Exception e) {
                log.warn("Could not generate past payroll run during seeding: {}", e.getMessage());
            }
        }

        log.info("Demo data seeding completed successfully with 1 manager and 7 employees across all 3 retention bands.");
    }
}
