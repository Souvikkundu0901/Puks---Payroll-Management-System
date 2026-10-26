package com.octopyder.payroll.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessClockTest {

    @Test
    void defaultClockUsesAsiaKolkata() {
        BusinessClock clock = new BusinessClock();
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(clock.today()).isNotNull();
        assertThat(clock.instant()).isNotNull();
        assertThat(clock.currentYearMonth()).isEqualTo(YearMonth.now(ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void fixedClockReturnsDeterministicValues() {
        Instant fixedInstant = Instant.parse("2026-10-26T10:00:00Z");
        BusinessClock clock = BusinessClock.fixed(fixedInstant);

        assertThat(clock.instant()).isEqualTo(fixedInstant);
        // 10:00:00 UTC = 15:30:00 IST on 2026-10-26
        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(clock.currentYearMonth()).isEqualTo(YearMonth.of(2026, 10));
    }
}
