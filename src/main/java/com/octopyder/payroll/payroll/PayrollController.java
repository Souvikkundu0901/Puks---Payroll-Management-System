package com.octopyder.payroll.payroll;

import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.payroll.dto.PayrollPreviewRequest;
import com.octopyder.payroll.payroll.dto.PayrollPreviewResponse;
import com.octopyder.payroll.payroll.dto.PayrollRecordResponse;
import com.octopyder.payroll.payroll.dto.PayrollRejectRequest;
import com.octopyder.payroll.payroll.dto.PayrollReleaseAllRequest;
import com.octopyder.payroll.payroll.dto.PayrollReleaseAllResponse;
import com.octopyder.payroll.payroll.dto.PayrollReleaseRequest;
import com.octopyder.payroll.payroll.dto.PayrollRunRequest;
import com.octopyder.payroll.payroll.dto.PayrollRunResponse;
import com.octopyder.payroll.payroll.dto.PayrollSummaryResponse;
import com.octopyder.payroll.payroll.dto.RetentionDecisionRequest;
import com.octopyder.payroll.payroll.dto.RetentionResponse;
import com.octopyder.payroll.payroll.service.PayrollService;
import com.octopyder.payroll.payroll.service.PayrollWorkflowService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payroll")
public class PayrollController {

    private final PayrollService payrollService;
    private final PayrollWorkflowService workflowService;

    public PayrollController(PayrollService payrollService, PayrollWorkflowService workflowService) {
        this.payrollService = payrollService;
        this.workflowService = workflowService;
    }

    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PayrollPreviewResponse> preview(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody PayrollPreviewRequest request
    ) {
        PayrollPreviewResponse response = payrollService.previewPayroll(currentUser, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/runs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PayrollRunResponse> generateRun(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody PayrollRunRequest request
    ) {
        PayrollRunResponse response = workflowService.generateRun(currentUser, request);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(response);
    }

    @GetMapping("/records")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<PageResponse<PayrollRecordResponse>> getRecords(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) String month,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) PayrollRecordStatus status,
        @RequestParam(required = false) String band,
        @RequestParam(required = false) String q,
        @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(workflowService.getRecords(currentUser, month, departmentId, status, band, q, pageable));
    }

    @GetMapping("/records/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    public ResponseEntity<PayrollRecordResponse> getRecord(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return ResponseEntity.ok(workflowService.getRecordById(currentUser, id));
    }

    @PostMapping("/records/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<PayrollRecordResponse> approveRecord(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return ResponseEntity.ok(workflowService.approveRecord(currentUser, id));
    }

    @PostMapping("/records/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<PayrollRecordResponse> rejectRecord(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody PayrollRejectRequest request
    ) {
        return ResponseEntity.ok(workflowService.rejectRecord(currentUser, id, request));
    }

    @PostMapping("/records/{id}/release-default")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PayrollRecordResponse> releaseDefault(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody PayrollReleaseRequest request
    ) {
        return ResponseEntity.ok(workflowService.releaseDefault(currentUser, id, request));
    }

    @PostMapping("/records/{id}/release")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PayrollRecordResponse> releaseRemainder(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody PayrollReleaseRequest request
    ) {
        return ResponseEntity.ok(workflowService.releaseRemainder(currentUser, id, request));
    }

    @PostMapping("/runs/{id}/release-all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PayrollReleaseAllResponse> releaseAllWithId(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody PayrollReleaseRequest request
    ) {
        PayrollReleaseAllRequest allRequest = new PayrollReleaseAllRequest(id, null, request.paymentDate(), request.paymentRef(), request.paymentMethod());
        return ResponseEntity.ok(workflowService.releaseAll(currentUser, allRequest));
    }

    @PostMapping("/runs/release-all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PayrollReleaseAllResponse> releaseAll(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody PayrollReleaseAllRequest request
    ) {
        return ResponseEntity.ok(workflowService.releaseAll(currentUser, request));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<PayrollSummaryResponse> getSummary(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam String month
    ) {
        return ResponseEntity.ok(workflowService.getSummary(currentUser, month));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER')")
    public ResponseEntity<List<PayrollRecordResponse>> getMe(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) Integer year
    ) {
        return ResponseEntity.ok(workflowService.getMeHistory(currentUser, year));
    }

    @GetMapping("/me/{month}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER')")
    public ResponseEntity<PayrollRecordResponse> getMeMonth(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable String month
    ) {
        return ResponseEntity.ok(workflowService.getMeMonth(currentUser, month));
    }

    @GetMapping("/me/{month}/payslip.pdf")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER')")
    public ResponseEntity<byte[]> getMyPayslipPdf(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable String month
    ) {
        return workflowService.getMyPayslipPdf(currentUser, month);
    }

    @GetMapping("/records/{id}/payslip.pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    public ResponseEntity<byte[]> getPayslipPdf(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return workflowService.getPayslipPdf(currentUser, id);
    }

    @GetMapping("/retentions")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<List<RetentionResponse>> getRetentions(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) RetentionStatus status
    ) {
        return ResponseEntity.ok(workflowService.getRetentions(currentUser, status));
    }

    @PostMapping("/retentions/{id}/release")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<RetentionResponse> releaseRetention(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody RetentionDecisionRequest request
    ) {
        return ResponseEntity.ok(workflowService.decideRetention(currentUser, id, true, request.note()));
    }

    @PostMapping("/retentions/{id}/forfeit")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<RetentionResponse> forfeitRetention(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody RetentionDecisionRequest request
    ) {
        return ResponseEntity.ok(workflowService.decideRetention(currentUser, id, false, request.note()));
    }

    @PostMapping("/manager/bulk-approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Map<String, Object>> bulkApproveTeam(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam String month
    ) {
        int approved = workflowService.bulkApproveTeamRecords(currentUser, month);
        return ResponseEntity.ok(Map.of("month", month, "approvedCount", approved));
    }

    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public void export(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam String format,
        @RequestParam(required = false) String month,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) PayrollRecordStatus status,
        HttpServletResponse response
    ) {
        workflowService.exportPayroll(currentUser, format, month, departmentId, status, response);
    }
}
