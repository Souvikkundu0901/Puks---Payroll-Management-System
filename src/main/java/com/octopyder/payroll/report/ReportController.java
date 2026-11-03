package com.octopyder.payroll.report;

import com.octopyder.payroll.common.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/{type}")
    @PreAuthorize("hasRole('ADMIN')")
    public void exportReport(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable String type,
        @RequestParam(defaultValue = "csv") String format,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String month,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID userId,
        @RequestParam(required = false) String status,
        HttpServletResponse response
    ) {
        reportService.exportReport(currentUser, type, format, from, to, month, departmentId, userId, status, response);
    }
}
