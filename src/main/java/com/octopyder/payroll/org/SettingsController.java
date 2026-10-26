package com.octopyder.payroll.org;

import com.octopyder.payroll.common.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> getAllSettings() {
        return settingsService.getAllSettings();
    }

    @GetMapping("/settings/{group}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> getSettingsGroup(@PathVariable String group) {
        return settingsService.getGroup(group);
    }

    @PutMapping("/settings/{group}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> updateSettingsGroup(
        @PathVariable String group,
        @RequestBody Map<String, Object> updates,
        @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return settingsService.updateGroup(group, updates, currentUser.id());
    }

    @PostMapping(value = "/settings/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> uploadLogo(
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal CurrentUser currentUser
    ) {
        Map<String, String> resp = settingsService.updateLogo(file, currentUser.id());
        return ResponseEntity.ok()
            .header("X-Content-Type-Options", "nosniff")
            .body(resp);
    }

    @GetMapping("/app-config")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getAppConfig() {
        return settingsService.getAppConfig();
    }
}
