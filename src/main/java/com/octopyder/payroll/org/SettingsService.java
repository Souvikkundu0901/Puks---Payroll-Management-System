package com.octopyder.payroll.org;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.storage.StorageService;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SettingsService {

    private final SystemSettingRepository settingRepository;
    private final SettingsValidator settingsValidator;
    private final StorageService storageService;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    private static final byte[] PNG_MAGIC = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    public SettingsService(
        SystemSettingRepository settingRepository,
        SettingsValidator settingsValidator,
        StorageService storageService,
        AuditService auditService,
        BusinessClock businessClock
    ) {
        this.settingRepository = settingRepository;
        this.settingsValidator = settingsValidator;
        this.storageService = storageService;
        this.auditService = auditService;
        this.businessClock = businessClock;
    }

    @PostConstruct
    public void loadCache() {
        refreshCache();
    }

    public synchronized void refreshCache() {
        cache.clear();
        // Load defaults first
        for (SettingDefinition def : SettingDefinition.getRegistry().values()) {
            if (def.defaultValue() != null) {
                cache.put(def.key(), def.defaultValue());
            }
        }
        // Overlay from DB
        List<SystemSetting> all = settingRepository.findAll();
        for (SystemSetting s : all) {
            Object parsed = parseValue(s.getValue(), s.getKey());
            if (parsed != null) {
                cache.put(s.getKey(), parsed);
            }
        }
    }

    public Object get(String key) {
        Object val = cache.get(key);
        if (val != null) {
            return val;
        }
        return settingRepository.findById(key)
            .map(s -> {
                Object parsed = parseValue(s.getValue(), key);
                if (parsed != null) {
                    cache.put(key, parsed);
                }
                return parsed;
            })
            .orElseGet(() -> {
                SettingDefinition def = SettingDefinition.getRegistry().get(key);
                return def != null ? def.defaultValue() : null;
            });
    }

    public String getString(String key, String defaultVal) {
        Object v = get(key);
        return v != null ? v.toString() : defaultVal;
    }

    public int getInt(String key, int defaultVal) {
        Object v = get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultVal;
    }

    public boolean getBoolean(String key, boolean defaultVal) {
        Object v = get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v != null) {
            return Boolean.parseBoolean(v.toString());
        }
        return defaultVal;
    }

    public Map<String, Object> getAllSettings() {
        Map<String, Object> result = new HashMap<>();
        for (String k : SettingDefinition.getRegistry().keySet()) {
            result.put(k, get(k));
        }
        return result;
    }

    public Map<String, Object> getGroup(String group) {
        if (!SettingDefinition.getAllowedGroups().contains(group)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_GROUP", "Invalid settings group: " + group);
        }
        Map<String, Object> result = new HashMap<>();
        String prefix = group + ".";
        for (String k : SettingDefinition.getRegistry().keySet()) {
            if (k.startsWith(prefix)) {
                result.put(k, get(k));
            }
        }
        return result;
    }

    public Map<String, Object> getAppConfig() {
        Map<String, Object> result = new HashMap<>();
        for (String k : SettingDefinition.getSafeAppConfigKeys()) {
            result.put(k, get(k));
        }
        return result;
    }

    public boolean isCompanyLogoKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return false;
        }
        Object logoObj = get("company.logo");
        if (logoObj instanceof String logoUrl) {
            return logoUrl.endsWith("/" + storageKey) || logoUrl.equals(storageKey);
        }
        return false;
    }

    @Transactional
    public Map<String, Object> updateGroup(String group, Map<String, Object> updates, UUID actorId) {
        settingsValidator.validate(group, updates);

        Map<String, Object> beforeMap = new HashMap<>();
        Map<String, Object> afterMap = new HashMap<>();

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            Object newVal = entry.getValue();

            beforeMap.put(key, get(key));

            String jsonVal = toJson(newVal);
            SystemSetting setting = settingRepository.findById(key)
                .orElse(new SystemSetting(key, jsonVal, actorId, businessClock.instant()));
            setting.setValue(jsonVal);
            setting.setUpdatedBy(actorId);
            setting.setUpdatedAt(businessClock.instant());
            settingRepository.save(setting);

            afterMap.put(key, newVal);
        }

        // Update cache immediately and ensure synchronization
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            cache.put(entry.getKey(), entry.getValue());
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        refreshCache();
                    }
                }
            });
        }

        // Audit before and after snapshots
        String beforeJson = toJson(beforeMap);
        String afterJson = toJson(afterMap);
        auditService.log("UPDATE_SETTINGS", "SETTINGS", group, beforeJson, afterJson);

        return getGroup(group);
    }

    @Transactional
    public Map<String, String> updateLogo(MultipartFile file, UUID actorId) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE", "Logo file cannot be empty");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_FILE_TOO_LARGE", "Logo size exceeds 2 MB limit");
        }

        String filename = file.getOriginalFilename();
        String ext = filename != null && filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE_TYPE", "Logo must be PNG or JPEG image");
        }

        try {
            byte[] fileBytes = file.getBytes();
            validateLogoMagicBytes(fileBytes, ext);

            String storageKey = storageService.store(
                filename,
                file.getInputStream(),
                file.getContentType(),
                file.getSize()
            );

            String logoUrl = "/api/v1/documents/stream/" + storageKey;
            updateGroup("company", Map.of("company.logo", logoUrl), actorId);

            return Map.of("logoUrl", logoUrl);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Failed to store logo: " + e.getMessage());
        }
    }

    private void validateLogoMagicBytes(byte[] bytes, String ext) {
        if (ext.equals("png")) {
            if (bytes.length < PNG_MAGIC.length) {
                throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE_TYPE", "File is not a valid PNG image");
            }
            for (int i = 0; i < PNG_MAGIC.length; i++) {
                if (bytes[i] != PNG_MAGIC[i]) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE_TYPE", "File is not a valid PNG image");
                }
            }
        } else {
            if (bytes.length < JPEG_MAGIC.length) {
                throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE_TYPE", "File is not a valid JPEG image");
            }
            for (int i = 0; i < JPEG_MAGIC.length; i++) {
                if (bytes[i] != JPEG_MAGIC[i]) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE_TYPE", "File is not a valid JPEG image");
                }
            }
        }
    }

    private Object parseValue(String json, String key) {
        if (json == null || json.equals("null")) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + value + "\"";
        }
    }
}
