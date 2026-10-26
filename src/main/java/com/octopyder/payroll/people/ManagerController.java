package com.octopyder.payroll.people;

import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.people.dto.AddManagerRequest;
import com.octopyder.payroll.people.dto.AssignTeamRequest;
import com.octopyder.payroll.people.dto.CreateEmployeeResponse;
import com.octopyder.payroll.people.dto.EmployeeDto;
import com.octopyder.payroll.people.dto.ManagerDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/managers")
public class ManagerController {

    private final ManagerService managerService;

    public ManagerController(ManagerService managerService) {
        this.managerService = managerService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<ManagerDto> listManagers() {
        return managerService.listManagers();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreateEmployeeResponse> createManager(@Valid @RequestBody AddManagerRequest request) {
        CreateEmployeeResponse response = managerService.createManager(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ManagerDto getManager(@PathVariable UUID id) {
        return managerService.getManager(id);
    }

    @GetMapping("/{id}/team")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public List<EmployeeDto> getTeam(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return managerService.getTeam(currentUser, id);
    }

    @PostMapping("/{id}/team")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.OK)
    public void assignTeam(
        @PathVariable UUID id,
        @Valid @RequestBody AssignTeamRequest request
    ) {
        managerService.assignTeam(id, request);
    }

    @DeleteMapping("/{id}/team/{employeeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromTeam(
        @PathVariable UUID id,
        @PathVariable UUID employeeId
    ) {
        managerService.removeFromTeam(id, employeeId);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteManager(@PathVariable UUID id) {
        managerService.deleteManager(id);
    }
}
