package com.octopyder.payroll.common;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Single injectable clock for the application.
 * All "now" and "today" calculations must use this clock to ensure determinism and testability.
 * Default time zone is Asia/Kolkata.
 */
@Component
public class BusinessClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private volatile Clock clock;

    public BusinessClock() {
        this(Clock.system(ZONE));
    }

    public BusinessClock(Clock clock) {
        this.clock = clock;
    }

    public void setFixed(Instant instant, ZoneId zone) {
        this.clock = Clock.fixed(instant, zone);
    }

    public void setFixed(Instant instant) {
        this.clock = Clock.fixed(instant, ZONE);
    }

    public void setFixed(LocalDate date) {
        this.clock = Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
    }

    public void reset() {
        this.clock = Clock.system(ZONE);
    }

    public static BusinessClock fixed(Instant instant, ZoneId zone) {
        return new BusinessClock(Clock.fixed(instant, zone));
    }

    public static BusinessClock fixed(Instant instant) {
        return fixed(instant, ZONE);
    }

    public static BusinessClock fixed(LocalDate date) {
        return fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
    }

    public Clock getClock() {
        return clock;
    }

    public ZoneId getZone() {
        return clock.getZone();
    }

    public Instant instant() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDateTime nowDateTime() {
        return LocalDateTime.now(clock);
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    public YearMonth currentYearMonth() {
        return YearMonth.now(clock);
    }
}
