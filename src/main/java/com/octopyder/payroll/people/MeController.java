package com.octopyder.payroll.people;

import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.people.dto.DocumentDto;
import com.octopyder.payroll.people.dto.ProfileDto;
import com.octopyder.payroll.people.dto.UpdateProfileRequest;
import com.octopyder.payroll.people.dto.UserPreferenceDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("isAuthenticated()")
public class MeController {

    private final ProfileService profileService;

    public MeController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/profile")
    public ProfileDto getProfile(@AuthenticationPrincipal CurrentUser currentUser) {
        return profileService.getProfile(currentUser);
    }

    @PutMapping("/profile")
    public ProfileDto updateProfile(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody UpdateProfileRequest request
    ) {
        return profileService.updateProfile(currentUser, request);
    }

    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileDto updatePhoto(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam("file") MultipartFile file
    ) {
        return profileService.updatePhoto(currentUser, file);
    }

    @GetMapping(value = "/id-card.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getMyIdCardPdf(@AuthenticationPrincipal CurrentUser currentUser) {
        byte[] pdfBytes = profileService.getIdCardPdf(currentUser, currentUser.id());
        return ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"id-card-" + currentUser.code() + ".pdf\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(pdfBytes);
    }

    @GetMapping("/preferences")
    public UserPreferenceDto getPreferences(@AuthenticationPrincipal CurrentUser currentUser) {
        return profileService.getPreferences(currentUser);
    }

    @PutMapping("/preferences")
    public UserPreferenceDto updatePreferences(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody UserPreferenceDto dto
    ) {
        return profileService.updatePreferences(currentUser, dto);
    }

    @GetMapping("/documents")
    public List<DocumentDto> listDocuments(@AuthenticationPrincipal CurrentUser currentUser) {
        return profileService.listDocuments(currentUser);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDto uploadDocument(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(value = "docType", defaultValue = "OTHER") String docType,
        @RequestParam("file") MultipartFile file
    ) {
        return profileService.uploadDocument(currentUser, docType, file);
    }
}
