package com.octopyder.payroll.org;

import com.octopyder.payroll.org.dto.BackupRunDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/backups")
public class BackupController {

    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<BackupRunDto> list() {
        return service.list();
    }

    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.CREATED)
    public BackupRunDto handleWebhook(
        @RequestHeader(value = "X-Backup-Secret", required = false) String xSecret,
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @Valid @RequestBody BackupRunDto.WebhookPayload payload
    ) {
        String secret = xSecret != null && !xSecret.isBlank() ? xSecret : authHeader;
        return service.handleWebhook(secret, payload);
    }
}
