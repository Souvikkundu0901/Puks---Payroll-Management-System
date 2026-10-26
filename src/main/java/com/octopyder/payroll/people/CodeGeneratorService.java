package com.octopyder.payroll.people;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CodeGeneratorService {

    private final JdbcTemplate jdbcTemplate;

    public CodeGeneratorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String nextEmployeeCode() {
        Long val = jdbcTemplate.queryForObject("SELECT nextval('seq_emp_code')", Long.class);
        return String.format("EMP%03d", val != null ? val : 1);
    }

    public String nextManagerCode() {
        Long val = jdbcTemplate.queryForObject("SELECT nextval('seq_mgr_code')", Long.class);
        return String.format("MGR%03d", val != null ? val : 1);
    }

    public String nextAdminCode() {
        Long val = jdbcTemplate.queryForObject("SELECT nextval('seq_adm_code')", Long.class);
        return String.format("ADM%03d", val != null ? val : 1);
    }
}
