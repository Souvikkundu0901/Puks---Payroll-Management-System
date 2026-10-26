package com.octopyder.payroll.people;

import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.people.dto.AddEmployeeRequest;
import com.octopyder.payroll.people.dto.BankAccountDto;
import com.octopyder.payroll.people.dto.BankAccountRequest;
import com.octopyder.payroll.people.dto.CreateEmployeeResponse;
import com.octopyder.payroll.people.dto.DeductionsRequest;
import com.octopyder.payroll.people.dto.DocumentDto;
import com.octopyder.payroll.people.dto.EmployeeDto;
import com.octopyder.payroll.people.dto.OffboardRequest;
import com.octopyder.payroll.people.dto.UpdateEmployeeRequest;
import com.octopyder.payroll.people.dto.VerifyDocumentRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeService employeeService;
    private final ProfileService profileService;

    public EmployeeController(EmployeeService employeeService, ProfileService profileService) {
        this.employeeService = employeeService;
        this.profileService = profileService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public PageResponse<EmployeeDto> listEmployees(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID managerId,
        @RequestParam(required = false) UserStatus status,
        @RequestParam(required = false) Role role,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        return employeeService.listEmployees(currentUser, q, departmentId, managerId, status, role, page, size);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreateEmployeeResponse> createEmployee(
        @Valid @RequestBody AddEmployeeRequest request,
        HttpServletRequest httpRequest
    ) {
        CreateEmployeeResponse response = employeeService.createEmployee(request, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.CACHE_CONTROL, "no-store") // BR-S8 temporary password protection
            .body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    public EmployeeDto getEmployee(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return employeeService.getEmployee(currentUser, id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeDto updateEmployee(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateEmployeeRequest request
    ) {
        return employeeService.updateEmployee(id, request);
    }

    @PostMapping("/{id}/offboard")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeDto offboardEmployee(
        @PathVariable UUID id,
        @Valid @RequestBody OffboardRequest request
    ) {
        return employeeService.offboardEmployee(id, request);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreateEmployeeResponse> resetPassword(@PathVariable UUID id) {
        CreateEmployeeResponse response = employeeService.resetPassword(id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEmployee(@PathVariable UUID id) {
        employeeService.deleteEmployee(id);
    }

    @GetMapping("/{id}/summary/{tab}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    public Map<String, Object> getSummary(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @PathVariable String tab
    ) {
        return employeeService.getSummary(currentUser, id, tab);
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'EMPLOYEE')")
    public List<DocumentDto> listDocuments(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return profileService.listEmployeeDocuments(currentUser, id);
    }

    @PostMapping("/{id}/documents/{docId}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public DocumentDto verifyDocument(
        @PathVariable UUID id,
        @PathVariable UUID docId,
        @Valid @RequestBody VerifyDocumentRequest request
    ) {
        return profileService.verifyDocument(docId, request);
    }

    @GetMapping("/{id}/bank-account")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public BankAccountDto getBankAccount(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return employeeService.getBankAccount(currentUser, id);
    }

    @PutMapping("/{id}/bank-account")
    @PreAuthorize("hasRole('ADMIN')")
    public BankAccountDto updateBankAccount(
        @PathVariable UUID id,
        @Valid @RequestBody BankAccountRequest request
    ) {
        return employeeService.updateBankAccount(id, request);
    }

    @PutMapping("/{id}/deductions")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateDeductions(
        @PathVariable UUID id,
        @Valid @RequestBody DeductionsRequest request
    ) {
        employeeService.updateDeductions(id, request);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasRole('ADMIN')")
    public void exportEmployees(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"employees.csv\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        employeeService.exportEmployeesCsv(response.getOutputStream());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> importEmployees(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "CSV file cannot be empty");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_FILE_TOO_LARGE", "File size exceeds 5 MB limit");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_FILE_TYPE", "Only CSV files are allowed");
        }
        int count = employeeService.importEmployeesCsv(file.getInputStream());
        return Map.of("importedCount", count);
    }
}
