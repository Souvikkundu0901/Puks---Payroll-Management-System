package com.octopyder.payroll.people;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "bank_name", nullable = false, length = 128)
    private String bankName;

    @Column(name = "account_number_enc", nullable = false, columnDefinition = "TEXT")
    private String accountNumberEnc;

    @Column(name = "account_last4", nullable = false, length = 4)
    private String accountLast4;

    @Column(nullable = false, length = 16)
    private String ifsc;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public BankAccount() {
    }

    public BankAccount(UUID userId, String bankName, String accountNumberEnc, String accountLast4, String ifsc) {
        this.userId = userId;
        this.bankName = bankName;
        this.accountNumberEnc = accountNumberEnc;
        this.accountLast4 = accountLast4;
        this.ifsc = ifsc;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getAccountNumberEnc() {
        return accountNumberEnc;
    }

    public void setAccountNumberEnc(String accountNumberEnc) {
        this.accountNumberEnc = accountNumberEnc;
    }

    public String getAccountLast4() {
        return accountLast4;
    }

    public void setAccountLast4(String accountLast4) {
        this.accountLast4 = accountLast4;
    }

    public String getIfsc() {
        return ifsc;
    }

    public void setIfsc(String ifsc) {
        this.ifsc = ifsc;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
