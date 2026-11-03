package com.octopyder.payroll.people;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdCardPdfServiceTest {

    private final IdCardPdfService idCardPdfService = new IdCardPdfService();

    @Test
    @DisplayName("Generates valid ID card PDF starting with %PDF magic header")
    void generatesValidIdCardPdf() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setCode("EMP002");
        user.setEmail("priya.patel@octopyder.com");
        user.setPasswordHash("hash");
        user.setRole(Role.EMPLOYEE);

        EmployeeProfile profile = new EmployeeProfile(userId, "Priya", "Patel", "Associate", BigDecimal.valueOf(50000));
        profile.setPhone("9876543210");
        profile.setJoiningDate(LocalDate.of(2026, 1, 15));
        profile.setBloodGroup("B+");
        profile.setEmergencyContact("9123456780");

        byte[] pdfBytes = idCardPdfService.generateIdCardPdf(user, profile, "Operations", "Puk's");

        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(100);
        // Verify PDF Magic Bytes: %PDF
        assertThat(pdfBytes[0]).isEqualTo((byte) 0x25);
        assertThat(pdfBytes[1]).isEqualTo((byte) 0x50);
        assertThat(pdfBytes[2]).isEqualTo((byte) 0x44);
        assertThat(pdfBytes[3]).isEqualTo((byte) 0x46);
    }
}
