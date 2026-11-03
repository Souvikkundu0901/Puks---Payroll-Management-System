package com.octopyder.payroll.dashboard;

import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.dashboard.dto.AdminDashboardDto;
import com.octopyder.payroll.dashboard.dto.EmployeeDashboardDto;
import com.octopyder.payroll.dashboard.dto.ManagerDashboardDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminDashboardDto> getAdminDashboard(@AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(dashboardService.getAdminDashboard(currentUser));
    }

    @GetMapping("/manager")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ManagerDashboardDto> getManagerDashboard(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) UUID managerId
    ) {
        return ResponseEntity.ok(dashboardService.getManagerDashboard(currentUser, managerId));
    }

    @GetMapping("/employee")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER')")
    public ResponseEntity<EmployeeDashboardDto> getEmployeeDashboard(@AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(dashboardService.getEmployeeDashboard(currentUser));
    }
}
