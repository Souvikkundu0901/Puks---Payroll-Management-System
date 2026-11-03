package com.octopyder.payroll.people;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class IdCardIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeProfileRepository profileRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String createTestUserToken(String code, String email, Role role, UUID managerId) {
        User user = new User();
        user.setCode(code);
        user.setEmail(email);
        user.setPasswordHash("$2a$12$abcdefghijklmnopqrstuu");
        user.setRole(role);
        user.setManagerId(managerId);
        user = userRepository.save(user);

        EmployeeProfile profile = new EmployeeProfile(user.getId(), "Test", "User", "Developer", BigDecimal.valueOf(50000));
        profileRepository.save(profile);

        return tokenService.generateAccessToken(user);
    }

    @Test
    @DisplayName("GET /api/v1/me/id-card.pdf returns 401 for unauthenticated requests")
    void unauthenticatedCannotDownloadIdCard() throws Exception {
        mockMvc.perform(get("/api/v1/me/id-card.pdf"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/me/id-card.pdf downloads valid PDF for authenticated employee")
    void employeeCanDownloadOwnIdCard() throws Exception {
        String empEmail = "id_card_emp_" + UUID.randomUUID() + "@octopyder.com";
        String token = createTestUserToken("IDC001", empEmail, Role.EMPLOYEE, null);

        MvcResult result = mockMvc.perform(get("/api/v1/me/id-card.pdf")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment; filename=\"id-card-IDC001.pdf\"")))
            .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotNull();
        assertThat(bytes.length).isGreaterThan(50);
        // Assert %PDF
        assertThat(bytes[0]).isEqualTo((byte) 0x25);
        assertThat(bytes[1]).isEqualTo((byte) 0x50);
        assertThat(bytes[2]).isEqualTo((byte) 0x44);
        assertThat(bytes[3]).isEqualTo((byte) 0x46);
    }

    @Test
    @DisplayName("GET /api/v1/employees/{id}/id-card.pdf prevents cross-employee access (403/404)")
    void crossEmployeeCannotDownloadOtherIdCard() throws Exception {
        String emp1Email = "id_card_emp1_" + UUID.randomUUID() + "@octopyder.com";
        String emp2Email = "id_card_emp2_" + UUID.randomUUID() + "@octopyder.com";

        String emp1Token = createTestUserToken("IDC002", emp1Email, Role.EMPLOYEE, null);
        createTestUserToken("IDC003", emp2Email, Role.EMPLOYEE, null);

        UUID emp2Id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, emp2Email);

        // Employee 1 trying to access Employee 2's ID Card
        mockMvc.perform(get("/api/v1/employees/" + emp2Id + "/id-card.pdf")
                .header("Authorization", "Bearer " + emp1Token))
            .andExpect(status().isForbidden());
    }
}
