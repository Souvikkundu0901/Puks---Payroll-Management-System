package com.octopyder.payroll.audit;

import com.octopyder.payroll.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLogsImmutabilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appendOnlyTriggerProhibitsUpdateAndDelete() {
        // 1. Insert an audit row
        jdbcTemplate.update(
            "INSERT INTO audit_logs (action, entity_type, entity_id) VALUES (?, ?, ?)",
            "TEST_ACTION", "TEST_ENTITY", "123"
        );

        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM audit_logs WHERE action = 'TEST_ACTION' AND entity_id = '123' ORDER BY id DESC LIMIT 1",
            Long.class
        );
        assertThat(id).isNotNull();

        // 2. Attempt UPDATE -> must throw exception from trigger
        assertThatThrownBy(() ->
            jdbcTemplate.update("UPDATE audit_logs SET action = 'TAMPERED' WHERE id = ?", id)
        ).hasMessageContaining("audit_logs table is append-only");

        // 3. Attempt DELETE -> must throw exception from trigger
        assertThatThrownBy(() ->
            jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", id)
        ).hasMessageContaining("audit_logs table is append-only");
    }
}
