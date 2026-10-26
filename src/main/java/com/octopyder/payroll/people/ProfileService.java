package com.octopyder.payroll.people;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.dto.DocumentDto;
import com.octopyder.payroll.people.dto.ProfileDto;
import com.octopyder.payroll.people.dto.UpdateProfileRequest;
import com.octopyder.payroll.people.dto.UserPreferenceDto;
import com.octopyder.payroll.people.dto.VerifyDocumentRequest;
import com.octopyder.payroll.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ProfileService {

    private static final Set<String> ALLOWED_DOC_TYPES = Set.of(
        "RESUME", "OFFER_LETTER", "ID_CARD", "CERTIFICATE", "PAN", "AADHAAR", "OTHER"
    );

    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeDocumentRepository documentRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final StorageService storageService;
    private final TeamScope teamScope;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final SettingsService settingsService;

    public ProfileService(
        UserRepository userRepository,
        EmployeeProfileRepository profileRepository,
        DepartmentRepository departmentRepository,
        EmployeeDocumentRepository documentRepository,
        UserPreferenceRepository preferenceRepository,
        StorageService storageService,
        TeamScope teamScope,
        AuditService auditService,
        BusinessClock businessClock,
        SettingsService settingsService
    ) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.departmentRepository = departmentRepository;
        this.documentRepository = documentRepository;
        this.preferenceRepository = preferenceRepository;
        this.storageService = storageService;
        this.teamScope = teamScope;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.settingsService = settingsService;
    }

    public ProfileDto getProfile(CurrentUser currentUser) {
        User user = userRepository.findById(currentUser.id())
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));

        EmployeeProfile profile = profileRepository.findById(user.getId()).orElse(null);
        return toProfileDto(user, profile);
    }

    @Transactional
    public ProfileDto updateProfile(CurrentUser currentUser, UpdateProfileRequest request) {
        User user = userRepository.findById(currentUser.id())
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));

        EmployeeProfile profile = profileRepository.findById(user.getId())
            .orElseGet(() -> new EmployeeProfile(user.getId(), "User", user.getCode(), "Employee", BigDecimal.ZERO));

        // Per D14: employees may edit ONLY phone, address, emergency contact, marital status, skills
        // Name, email, department, salary are protected from user modification
        if (request.phone() != null) profile.setPhone(request.phone());
        if (request.address() != null) profile.setAddress(request.address());
        if (request.emergencyContact() != null) profile.setEmergencyContact(request.emergencyContact());
        if (request.maritalStatus() != null) profile.setMaritalStatus(request.maritalStatus());
        if (request.skills() != null) profile.setSkills(request.skills());

        profile.setUpdatedAt(businessClock.instant());
        profileRepository.save(profile);

        auditService.log("UPDATE_PROFILE", "USER", user.getId().toString(), null, "Profile updated by self");

        return toProfileDto(user, profile);
    }

    @Transactional
    public ProfileDto updatePhoto(CurrentUser currentUser, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE", "Photo file cannot be empty");
        }

        try (InputStream in = file.getInputStream()) {
            String storageKey = storageService.store(
                file.getOriginalFilename(),
                in,
                file.getContentType(),
                file.getSize()
            );

            EmployeeProfile profile = profileRepository.findById(currentUser.id())
                .orElseGet(() -> new EmployeeProfile(currentUser.id(), "User", "User", "Employee", BigDecimal.ZERO));

            // Delete old photo if present
            if (profile.getPhotoKey() != null) {
                storageService.delete(profile.getPhotoKey());
            }

            profile.setPhotoKey(storageKey);
            profile.setUpdatedAt(businessClock.instant());
            profileRepository.save(profile);

            auditService.log("UPDATE_PHOTO", "USER", currentUser.id().toString(), null, "Photo updated");

            User user = userRepository.findById(currentUser.id()).orElseThrow();
            return toProfileDto(user, profile);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Failed to upload photo: " + e.getMessage());
        }
    }

    public UserPreferenceDto getPreferences(CurrentUser currentUser) {
        return preferenceRepository.findById(currentUser.id())
            .map(p -> new UserPreferenceDto(p.getTheme(), p.isNotifyEmail(), p.isNotifyPush(), p.isNotifyPayroll()))
            .orElse(new UserPreferenceDto("default", true, true, true));
    }

    @Transactional
    public UserPreferenceDto updatePreferences(CurrentUser currentUser, UserPreferenceDto dto) {
        UserPreference pref = preferenceRepository.findById(currentUser.id())
            .orElseGet(() -> new UserPreference(currentUser.id()));

        if (dto.theme() != null) pref.setTheme(dto.theme());
        pref.setNotifyEmail(dto.notifyEmail());
        pref.setNotifyPush(dto.notifyPush());
        pref.setNotifyPayroll(dto.notifyPayroll());
        pref.setUpdatedAt(businessClock.instant());

        preferenceRepository.save(pref);
        return dto;
    }

    public List<DocumentDto> listDocuments(CurrentUser currentUser) {
        return listEmployeeDocuments(currentUser, currentUser.id());
    }

    public List<DocumentDto> listEmployeeDocuments(CurrentUser currentUser, UUID employeeId) {
        teamScope.assertCanAccess(currentUser, employeeId);

        List<EmployeeDocument> docs = documentRepository.findByUserIdOrderByUploadedAtDesc(employeeId);
        List<DocumentDto> dtos = new ArrayList<>();
        for (EmployeeDocument doc : docs) {
            dtos.add(new DocumentDto(
                doc.getId(),
                doc.getUserId(),
                doc.getDocType(),
                doc.getOriginalName(),
                doc.getContentType(),
                doc.getSizeBytes(),
                doc.getVerificationStatus(),
                doc.getVerifiedBy(),
                doc.getUploadedAt(),
                "/api/v1/documents/" + doc.getId() + "/download"
            ));
        }
        return dtos;
    }

    @Transactional
    public DocumentDto uploadDocument(CurrentUser currentUser, String docType, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE", "Document file cannot be empty");
        }

        String normalizedDocType = docType != null ? docType.trim().toUpperCase() : "OTHER";
        if (normalizedDocType.length() > 32 || !ALLOWED_DOC_TYPES.contains(normalizedDocType)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_DOC_TYPE", "Invalid document type: " + docType);
        }

        try (InputStream in = file.getInputStream()) {
            String storageKey = storageService.store(
                file.getOriginalFilename(),
                in,
                file.getContentType(),
                file.getSize()
            );

            EmployeeDocument doc = new EmployeeDocument(
                currentUser.id(),
                normalizedDocType,
                storageKey,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize()
            );

            EmployeeDocument saved = documentRepository.save(doc);
            auditService.log("UPLOAD_DOCUMENT", "DOCUMENT", saved.getId().toString(), null, "{\"docType\":\"" + doc.getDocType() + "\"}");

            return new DocumentDto(
                saved.getId(),
                saved.getUserId(),
                saved.getDocType(),
                saved.getOriginalName(),
                saved.getContentType(),
                saved.getSizeBytes(),
                saved.getVerificationStatus(),
                saved.getVerifiedBy(),
                saved.getUploadedAt(),
                "/api/v1/documents/" + saved.getId() + "/download"
            );
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Failed to upload document: " + e.getMessage());
        }
    }

    public EmployeeDocument getDocumentForDownload(CurrentUser currentUser, UUID docId) {
        EmployeeDocument doc = documentRepository.findById(docId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Document not found"));

        // Authorization check: Owner or Admin only! (Manager cannot download employee personal docs)
        if (currentUser.role() != Role.ADMIN && !doc.getUserId().equals(currentUser.id())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Unauthorized to download this document");
        }

        auditService.log("DOWNLOAD_DOCUMENT", "DOCUMENT", docId.toString(), null, "{\"fileName\":\"" + doc.getOriginalName() + "\"}");
        return doc;
    }

    public void validateStorageKeyAccess(CurrentUser currentUser, String storageKey) {
        Optional<EmployeeDocument> docOpt = documentRepository.findByStorageKey(storageKey);
        if (docOpt.isPresent()) {
            EmployeeDocument doc = docOpt.get();
            if (currentUser.role() != Role.ADMIN && !doc.getUserId().equals(currentUser.id())) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Unauthorized to view this document");
            }
            return;
        }

        if (profileRepository.existsByPhotoKey(storageKey)) {
            return;
        }

        if (settingsService.isCompanyLogoKey(storageKey)) {
            return;
        }

        throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "File not found");
    }

    @Transactional
    public DocumentDto verifyDocument(UUID docId, VerifyDocumentRequest request) {
        EmployeeDocument doc = documentRepository.findById(docId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Document not found"));

        doc.setVerificationStatus(request.status().toUpperCase());
        documentRepository.save(doc);

        auditService.log("VERIFY_DOCUMENT", "DOCUMENT", docId.toString(), null, "{\"status\":\"" + request.status() + "\"}");

        return new DocumentDto(
            doc.getId(),
            doc.getUserId(),
            doc.getDocType(),
            doc.getOriginalName(),
            doc.getContentType(),
            doc.getSizeBytes(),
            doc.getVerificationStatus(),
            doc.getVerifiedBy(),
            doc.getUploadedAt(),
            "/api/v1/documents/" + doc.getId() + "/download"
        );
    }

    private ProfileDto toProfileDto(User user, EmployeeProfile profile) {
        String deptName = "";
        if (profile != null && profile.getDepartmentId() != null) {
            deptName = departmentRepository.findById(profile.getDepartmentId()).map(Department::getName).orElse("");
        }

        String mgrName = "";
        if (user.getManagerId() != null) {
            mgrName = profileRepository.findById(user.getManagerId())
                .map(p -> p.getFirstName() + " " + p.getLastName())
                .orElse("");
        }

        String fullName = profile != null ? profile.getFirstName() + " " + profile.getLastName() : user.getCode();
        String photoUrl = profile != null && profile.getPhotoKey() != null ? "/api/v1/documents/stream/" + profile.getPhotoKey() : null;

        return new ProfileDto(
            user.getId(),
            user.getCode(),
            profile != null ? profile.getFirstName() : "",
            profile != null ? profile.getLastName() : "",
            fullName,
            user.getEmail(),
            profile != null ? profile.getPhone() : null,
            profile != null ? profile.getAddress() : null,
            profile != null ? profile.getGender() : null,
            profile != null ? profile.getDateOfBirth() : null,
            profile != null ? profile.getBloodGroup() : null,
            profile != null ? profile.getEmergencyContact() : null,
            profile != null ? profile.getMaritalStatus() : null,
            profile != null ? profile.getDepartmentId() : null,
            deptName,
            profile != null ? profile.getDesignation() : "",
            profile != null ? profile.getEmploymentType() : "FULL_TIME",
            profile != null ? profile.getJoiningDate() : null,
            user.getManagerId(),
            mgrName,
            profile != null ? profile.getBaseSalary() : BigDecimal.ZERO,
            profile != null ? profile.getExperienceYears() : BigDecimal.ZERO,
            profile != null ? profile.getSkills() : new String[0],
            user.getRole(),
            user.getStatus(),
            photoUrl
        );
    }
}
