package com.octopyder.payroll.report;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ReportIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeProfileRepository profileRepository;

    @Autowired
    private BusinessClock businessClock;

    private User admin;
    private User employee;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE tasks, task_activities, task_comments, task_attachments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE leave_requests, leave_ledger_entries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE payroll_records, payroll_runs, payroll_lines, payroll_retentions CASCADE");
        jdbcTemplate.execute("DELETE FROM attendance_records");
        jdbcTemplate.execute("DELETE FROM holidays");
        jdbcTemplate.execute("TRUNCATE TABLE users, employee_profiles CASCADE");

        admin = createUser("REP_ADM", "admin.rep@example.com", Role.ADMIN);
        employee = createUser("=REP_EMP", "emp.rep@example.com", Role.EMPLOYEE); // Dangerous prefix to test sanitization

        createProfile(admin.getId(), "Admin", "User");
        createProfile(employee.getId(), "+Alice", "-FormulaInjection");

        adminToken = tokenService.generateAccessToken(admin);
        employeeToken = tokenService.generateAccessToken(employee);
    }

    private User createUser(String code, String email, Role role) {
        User u = new User();
        u.setCode(code);
        u.setEmail(email);
        u.setPasswordHash("$2a$12$eACCgG32508QZkF/5.8.hO0vB8g50s5yY/3E.J4b5749vVb/kLgVq");
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setTokenVersion(1);
        u.setMustChangePassword(false);
        u.setCreatedAt(businessClock.instant());
        return userRepository.save(u);
    }

    private void createProfile(UUID userId, String first, String last) {
        EmployeeProfile p = new EmployeeProfile();
        p.setUserId(userId);
        p.setFirstName(first);
        p.setLastName(last);
        p.setDesignation("Developer");
        p.setJoiningDate(LocalDate.of(2025, 1, 1));
        profileRepository.save(p);
    }

    @Test
    @DisplayName("Unauthenticated request to reports should return 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/reports/employees").param("format", "csv"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Non-Admin user requesting report should return 403")
    void nonAdminReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/reports/employees")
                .header("Authorization", "Bearer " + employeeToken)
                .param("format", "csv"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Invalid format should return 400")
    void invalidFormatReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reports/employees")
                .header("Authorization", "Bearer " + adminToken)
                .param("format", "docx"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_FORMAT"));
    }

    @Test
    @DisplayName("Invalid report type should return 404")
    void invalidReportTypeReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/reports/nonexistent")
                .header("Authorization", "Bearer " + adminToken)
                .param("format", "csv"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPORT_TYPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Attendance report exceeding 366 days should return 400")
    void attendanceRangeExceededReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reports/attendance")
                .header("Authorization", "Bearer " + adminToken)
                .param("format", "csv")
                .param("from", "2025-01-01")
                .param("to", "2026-03-01")) // 425 days
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REPORT_RANGE_EXCEEDED"));
    }

    @Test
    @DisplayName("Export employees CSV sanitizes dangerous characters and sets no-store header")
    void exportEmployeesCsvSanitizesDangerousChars() throws Exception {
        String csvContent = mockMvc.perform(get("/api/v1/reports/employees")
                .header("Authorization", "Bearer " + adminToken)
                .param("format", "csv"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Content-Disposition"))
            .andReturn().getResponse().getContentAsString();

        // Check formula injection escaping
        assertThat(csvContent).contains("'=REP_EMP"); // starts with = escaped to '=
        assertThat(csvContent).contains("'+Alice");     // starts with + escaped to '+
    }

    @Test
    @DisplayName("Export tasks XLSX returns 200 with proper media type")
    void exportTasksXlsxReturns200() throws Exception {
        mockMvc.perform(get("/api/v1/reports/tasks")
                .header("Authorization", "Bearer " + adminToken)
                .param("format", "xlsx"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }
}
