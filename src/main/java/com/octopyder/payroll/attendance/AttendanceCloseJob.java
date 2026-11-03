package com.octopyder.payroll.attendance;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.org.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class AttendanceCloseJob {

    private static final Logger log = LoggerFactory.getLogger(AttendanceCloseJob.class);

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final BusinessClock businessClock;
    private final SettingsService settingsService;
    private final AuditService auditService;

    public AttendanceCloseJob(
        AttendanceRecordRepository attendanceRecordRepository,
        BusinessClock businessClock,
        SettingsService settingsService,
        AuditService auditService
    ) {
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.businessClock = businessClock;
        this.settingsService = settingsService;
        this.auditService = auditService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Running attendance close catch-up on application startup");
        closeOpenRecordsOlderThanToday();
    }

    @Scheduled(cron = "0 5 * * * ?")
    public void runScheduledCloseJob() {
        log.info("Running scheduled attendance close job");
        closeOpenRecordsOlderThanToday();
    }

    @Transactional
    public int closeOpenRecordsOlderThanToday() {
        String timezoneStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zone = ZoneId.of(timezoneStr);
        LocalDate today = businessClock.instant().atZone(zone).toLocalDate();

        List<AttendanceRecord> openRecords = attendanceRecordRepository
            .findByWorkDateBeforeAndCheckOutAtIsNullAndMissedCheckoutIsFalse(today);

        if (openRecords.isEmpty()) {
            return 0;
        }

        log.info("Closing {} unclosed attendance records with MISSED_CHECKOUT", openRecords.size());
        for (AttendanceRecord record : openRecords) {
            record.setMissedCheckout(true);
            record.setUpdatedAt(businessClock.instant());
            attendanceRecordRepository.save(record);

            auditService.log(
                "ATTENDANCE_MISSED_CHECKOUT",
                "ATTENDANCE",
                record.getId().toString(),
                "{\"missedCheckout\":false}",
                "{\"missedCheckout\":true,\"workDate\":\"" + record.getWorkDate() + "\"}"
            );
        }

        return openRecords.size();
    }
}
