package com.octopyder.payroll.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "system_settings")
public class SystemSetting {

    @Id
    @Column(name = "key", length = 128)
    private String key;

    @Column(name = "value", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String value;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SystemSetting() {
    }

    public SystemSetting(String key, String value, UUID updatedBy, Instant updatedAt) {
        this.key = key;
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
