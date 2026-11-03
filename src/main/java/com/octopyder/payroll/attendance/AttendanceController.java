package com.octopyder.payroll.attendance;

import com.octopyder.payroll.attendance.dto.AttendanceBulkDecisionRequest;
import com.octopyder.payroll.attendance.dto.AttendanceRejectRequest;
import com.octopyder.payroll.attendance.dto.AttendanceResponse;
import com.octopyder.payroll.attendance.dto.AttendanceSummaryDto;
import com.octopyder.payroll.attendance.dto.AttendanceTodayDto;
import com.octopyder.payroll.attendance.dto.BulkDecisionResult;
import com.octopyder.payroll.attendance.dto.CheckInRequest;
import com.octopyder.payroll.attendance.dto.CheckOutRequest;
import com.octopyder.payroll.attendance.dto.MonthAttendanceSummaryDto;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @PostMapping("/check-in")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public AttendanceResponse checkIn(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestBody CheckInRequest request,
        HttpServletRequest httpRequest
    ) {
        return attendanceService.checkIn(currentUser, request, httpRequest);
    }

    @PostMapping("/check-out")
    @PreAuthorize("isAuthenticated()")
    public AttendanceResponse checkOut(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestBody(required = false) CheckOutRequest request
    ) {
        return attendanceService.checkOut(currentUser, request != null ? request : new CheckOutRequest(null, null));
    }

    @GetMapping("/me/today")
    @PreAuthorize("isAuthenticated()")
    public AttendanceTodayDto getToday(@AuthenticationPrincipal CurrentUser currentUser) {
        return attendanceService.getToday(currentUser);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public MonthAttendanceSummaryDto getMonth(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) String month
    ) {
        return attendanceService.getMonth(currentUser, month);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public PageResponse<AttendanceResponse> listAttendance(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) UUID employeeId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String period,
        @RequestParam(required = false) AttendanceApprovalStatus approvalStatus,
        @RequestParam(required = false) Boolean isLate,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sort
    ) {
        return attendanceService.listAttendance(
            currentUser, employeeId, departmentId, from, to, period, approvalStatus, isLate, page, size, sort
        );
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public List<AttendanceResponse> getPending(@AuthenticationPrincipal CurrentUser currentUser) {
        return attendanceService.getPending(currentUser);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public AttendanceResponse approveAttendance(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return attendanceService.approveAttendance(currentUser, id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public AttendanceResponse rejectAttendance(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @RequestBody(required = false) AttendanceRejectRequest request
    ) {
        return attendanceService.rejectAttendance(currentUser, id, request);
    }

    @PostMapping("/bulk-decision")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public BulkDecisionResult bulkDecision(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody AttendanceBulkDecisionRequest request
    ) {
        return attendanceService.bulkDecision(currentUser, request);
    }

    @GetMapping("/employee/{userId}/timeline")
    @PreAuthorize("isAuthenticated()")
    public List<AttendanceResponse> getTimeline(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID userId,
        @RequestParam(required = false, defaultValue = "30") Integer days
    ) {
        return attendanceService.getTimeline(currentUser, userId, days);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public AttendanceSummaryDto getSummary(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return attendanceService.getSummary(currentUser, date);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<String> exportAttendance(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        String csv = attendanceService.exportAttendanceCsv(currentUser, departmentId, from, to);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendance.csv\"")
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(csv);
    }
}
