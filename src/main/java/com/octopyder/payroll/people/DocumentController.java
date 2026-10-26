package com.octopyder.payroll.people;

import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.storage.StorageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final ProfileService profileService;
    private final StorageService storageService;

    public DocumentController(ProfileService profileService, StorageService storageService) {
        this.profileService = profileService;
        this.storageService = storageService;
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadDocument(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        EmployeeDocument doc = profileService.getDocumentForDownload(currentUser, id);
        InputStream stream = storageService.load(doc.getStorageKey());

        // Sanitize filename to prevent header injection or directory traversal
        String safeName = doc.getOriginalName().replaceAll("[\\r\\n\"\\\\]", "_");

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(doc.getContentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(new InputStreamResource(stream));
    }

    @GetMapping("/stream/{storageKey}")
    public ResponseEntity<Resource> streamDocument(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable String storageKey
    ) {
        profileService.validateStorageKeyAccess(currentUser, storageKey);
        InputStream stream = storageService.load(storageKey);
        String contentType = storageKey.endsWith(".png") ? "image/png" : (storageKey.endsWith(".jpg") || storageKey.endsWith(".jpeg") ? "image/jpeg" : "application/pdf");

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header("X-Content-Type-Options", "nosniff")
            .body(new InputStreamResource(stream));
    }
}
