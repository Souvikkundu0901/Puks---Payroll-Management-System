package com.octopyder.payroll.people;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<Department> listDepartments() {
        return departmentService.listDepartments();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Department createDepartment(@Valid @RequestBody DepartmentRequest request) {
        return departmentService.createDepartment(request.code(), request.name());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Department updateDepartment(
        @PathVariable UUID id,
        @Valid @RequestBody DepartmentRequest request
    ) {
        return departmentService.updateDepartment(id, request.name(), request.active() != null ? request.active() : true);
    }

    public record DepartmentRequest(
        String code,
        @NotBlank String name,
        Boolean active
    ) {}
}
