package com.octopyder.payroll.payroll.service;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.payroll.calculator.PayrollCalculator;
import com.octopyder.payroll.payroll.calculator.PayrollInputs;
import com.octopyder.payroll.payroll.calculator.PayrollResult;
import com.octopyder.payroll.payroll.dto.PayrollPreviewRequest;
import com.octopyder.payroll.payroll.dto.PayrollPreviewResponse;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.User;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PayrollService {

    private final PayrollInputsAssembler assembler;
    private final AuditService auditService;
    private final Map<UUID, Bucket> adminBuckets = new ConcurrentHashMap<>();

    public PayrollService(PayrollInputsAssembler assembler, AuditService auditService) {
        this.assembler = assembler;
        this.auditService = auditService;
    }

    private Bucket createBucket() {
        Refill refill = Refill.intervally(60, Duration.ofMinutes(1));
        Bandwidth limit = Bandwidth.classic(60, refill);
        return Bucket.builder().addLimit(limit).build();
    }

    public void clearRateLimits() {
        adminBuckets.clear();
    }

    public PayrollPreviewResponse previewPayroll(CurrentUser currentUser, PayrollPreviewRequest request) {
        if (currentUser != null && currentUser.id() != null) {
            Bucket bucket = adminBuckets.computeIfAbsent(currentUser.id(), k -> createBucket());
            if (!bucket.tryConsume(1)) {
                throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many payroll preview requests. Please wait a moment.");
            }
        }

        AssembledPayrollInputs assembled = assembler.assemble(
            request.employeeId(),
            request.month(),
            request.isTreatPendingAsPresent()
        );

        PayrollInputs inputs = assembled.inputs();
        PayrollResult result = PayrollCalculator.calculate(inputs);

        User user = assembled.user();
        EmployeeProfile profile = assembled.profile();

        String employeeName = profile.getFirstName() != null
            ? (profile.getFirstName() + (profile.getLastName() != null ? " " + profile.getLastName() : ""))
            : user.getEmail();

        Map<String, BigDecimal> earningsBreakup = calculateEarningsBreakup(result.gross());

        auditService.log(
            "PAYROLL_PREVIEW",
            "PAYROLL",
            user.getId().toString(),
            null,
            "Preview calculated for " + user.getCode() + " (" + request.month().trim() + ")" +
                (request.isTreatPendingAsPresent() ? " [OVERRIDE: treatPendingAsPresent=true]" : "")
        );

        return new PayrollPreviewResponse(
            user.getId(),
            user.getCode(),
            employeeName,
            user.getRole().name(),
            request.month().trim(),
            assembled.cutoffDate(),
            result.gross(),
            inputs.tasksTotal(),
            inputs.tasksApproved(),
            result.completionPct(),
            result.retentionDays(),
            result.retentionAmount(),
            result.absentDays(),
            result.absentDeduction(),
            result.unpaidLeaveDays(),
            result.unpaidLeaveDeduction(),
            result.totalUnpaidDeduction(),
            result.tds(),
            result.pf(),
            result.pt(),
            result.statutoryTotal(),
            result.totalDeductions(),
            result.net(),
            result.defaultReleaseAmount(),
            result.taskLinkedAmount(),
            result.taskUnlockedAmount(),
            result.releasedPct(),
            result.tranche1(),
            result.tranche2(),
            result.isNetNegativeClamped(),
            earningsBreakup,
            assembled.pendingAttendance(),
            assembled.pendingLeave()
        );
    }

    private Map<String, BigDecimal> calculateEarningsBreakup(BigDecimal gross) {
        // D24: Gross split by payroll.breakupPercent (default Basic 50, HRA 20, Medical 10, Travel 10, Bonus 10)
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        map.put("Basic", gross.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP));
        map.put("HRA", gross.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP));
        map.put("Medical Allowance", gross.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP));
        map.put("Travel Allowance", gross.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP));
        map.put("Performance Bonus", gross.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP));
        return map;
    }
}
