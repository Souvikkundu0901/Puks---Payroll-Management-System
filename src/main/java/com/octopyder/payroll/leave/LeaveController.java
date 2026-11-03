package com.octopyder.payroll.leave;

import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.leave.dto.AdminLeaveApplyRequest;
import com.octopyder.payroll.leave.dto.LeaveApplyRequest;
import com.octopyder.payroll.leave.dto.LeaveBalanceDto;
import com.octopyder.payroll.leave.dto.LeaveBulkDecisionRequest;
import com.octopyder.payroll.leave.dto.LeaveBulkDecisionResult;
import com.octopyder.payroll.leave.dto.LeaveDecisionRequest;
import com.octopyder.payroll.leave.dto.LeaveLedgerDetailsDto;
import com.octopyder.payroll.leave.dto.LeaveLedgerEntryDto;
import com.octopyder.payroll.leave.dto.LeaveRejectRequest;
import com.octopyder.payroll.leave.dto.LeaveRequestResponse;
import com.octopyder.payroll.leave.dto.LeaveSummaryDto;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leave")
public class LeaveController {

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping("/me/balance")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER')")
    public LeaveBalanceDto getMyBalance(@AuthenticationPrincipal CurrentUser currentUser) {
        return leaveService.getMyBalance(currentUser);
    }

    @GetMapping("/me/ledger")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER')")
    public List<LeaveLedgerEntryDto> getMyLedger(@AuthenticationPrincipal CurrentUser currentUser) {
        return leaveService.getMyLedger(currentUser);
    }

    @GetMapping("/me/requests")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER')")
    public List<LeaveRequestResponse> getMyRequests(@AuthenticationPrincipal CurrentUser currentUser) {
        return leaveService.getMyRequests(currentUser);
    }

    @PostMapping(value = "/requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER')")
    public LeaveRequestResponse applyLeave(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(value = "type", required = false) LeaveType type,
        @RequestParam(value = "leaveType", required = false) LeaveType leaveType,
        @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam("reason") String reason,
        @RequestPart(value = "attachment", required = false) MultipartFile attachment
    ) {
        LeaveType finalType = type != null ? type : leaveType;
        if (finalType == null) {
            throw new com.octopyder.payroll.common.AppException(HttpStatus.BAD_REQUEST, "LEAVE_TYPE_REQUIRED", "Leave type is required");
        }
        LeaveApplyRequest request = new LeaveApplyRequest(finalType, startDate, endDate, reason);
        return leaveService.applyLeave(currentUser, request, attachment);
    }

    @PostMapping(value = "/requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER')")
    public LeaveRequestResponse applyLeaveJson(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody LeaveApplyRequest request
    ) {
        return leaveService.applyLeave(currentUser, request, null);
    }

    @PostMapping("/admin-apply")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public LeaveRequestResponse applyLeaveOnBehalf(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody AdminLeaveApplyRequest request
    ) {
        return leaveService.applyLeaveOnBehalf(currentUser, request, null);
    }

    @PostMapping("/requests/{id}/cancel")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER', 'ADMIN')")
    public LeaveRequestResponse cancelLeave(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return leaveService.cancelLeave(currentUser, id);
    }

    @GetMapping("/requests")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public PageResponse<LeaveRequestResponse> listLeaveRequests(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) LeaveStatus status,
        @RequestParam(required = false) LeaveType type,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) String month,
        @RequestParam(required = false) UUID employeeId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        return leaveService.listLeaveRequests(currentUser, status, type, departmentId, month, employeeId, page, size, sort);
    }

    @PostMapping("/requests/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public LeaveRequestResponse approveLeave(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody(required = false) LeaveDecisionRequest request
    ) {
        return leaveService.approveLeave(currentUser, id, request);
    }

    @PostMapping("/requests/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public LeaveRequestResponse rejectLeave(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody LeaveRejectRequest request
    ) {
        return leaveService.rejectLeave(currentUser, id, request);
    }

    @PostMapping("/requests/bulk-decision")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public LeaveBulkDecisionResult bulkDecision(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody LeaveBulkDecisionRequest request
    ) {
        return leaveService.bulkDecision(currentUser, request);
    }

    @GetMapping("/ledger/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public LeaveLedgerDetailsDto getEmployeeLedger(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID userId
    ) {
        return leaveService.getEmployeeLedger(currentUser, userId);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public LeaveSummaryDto getSummary(@AuthenticationPrincipal CurrentUser currentUser) {
        return leaveService.getSummary(currentUser);
    }

    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> exportLeave(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) LeaveStatus status,
        @RequestParam(required = false) LeaveType type,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) String month
    ) {
        String csv = leaveService.exportLeaveCsv(currentUser, status, type, departmentId, month);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"leave-export.csv\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(csv);
    }

    @GetMapping("/requests/{id}/attachment")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    public ResponseEntity<Resource> downloadAttachment(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        Resource resource = leaveService.loadAttachment(currentUser, id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"certificate.pdf\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(resource);
    }
}
