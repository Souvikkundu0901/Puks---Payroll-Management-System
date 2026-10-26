package com.octopyder.payroll.people;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final AuditService auditService;

    public DepartmentService(DepartmentRepository departmentRepository, AuditService auditService) {
        this.departmentRepository = departmentRepository;
        this.auditService = auditService;
    }

    public List<Department> listDepartments() {
        return departmentRepository.findByActiveTrue();
    }

    public Department getDepartment(UUID id) {
        return departmentRepository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Department not found"));
    }

    @Transactional
    public Department createDepartment(String code, String name) {
        if (departmentRepository.findByNameIgnoreCase(name).isPresent()) {
            throw new AppException(HttpStatus.CONFLICT, "DEPARTMENT_EXISTS", "Department name already exists");
        }
        Department dept = new Department(code, name);
        Department saved = departmentRepository.save(dept);
        auditService.log("CREATE_DEPARTMENT", "DEPARTMENT", saved.getId().toString(), null, "{\"name\":\"" + name + "\"}");
        return saved;
    }

    @Transactional
    public Department updateDepartment(UUID id, String name, boolean active) {
        Department dept = getDepartment(id);
        dept.setName(name);
        dept.setActive(active);
        Department saved = departmentRepository.save(dept);
        auditService.log("UPDATE_DEPARTMENT", "DEPARTMENT", saved.getId().toString(), null, "{\"name\":\"" + name + "\"}");
        return saved;
    }
}
