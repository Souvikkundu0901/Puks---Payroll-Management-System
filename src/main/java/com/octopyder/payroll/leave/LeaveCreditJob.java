package com.octopyder.payroll.leave;

import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.org.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class LeaveCreditJob {

    private static final Logger log = LoggerFactory.getLogger(LeaveCreditJob.class);

    private final LeaveLedgerService leaveLedgerService;
    private final SettingsService settingsService;
    private final BusinessClock businessClock;

    public LeaveCreditJob(
        LeaveLedgerService leaveLedgerService,
        SettingsService settingsService,
        BusinessClock businessClock
    ) {
        this.leaveLedgerService = leaveLedgerService;
        this.settingsService = settingsService;
        this.businessClock = businessClock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Running leave credit catch-up on application startup");
        LocalDate today = getToday();
        leaveLedgerService.ensureCreditsUpTo(today);
    }

    @Scheduled(cron = "0 10 0 1 * ?")
    public void runMonthlyCreditJob() {
        log.info("Running scheduled monthly leave credit job");
        LocalDate today = getToday();
        leaveLedgerService.ensureCreditsUpTo(today);
    }

    private LocalDate getToday() {
        String timezoneStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zone = ZoneId.of(timezoneStr);
        return businessClock.instant().atZone(zone).toLocalDate();
    }
}
