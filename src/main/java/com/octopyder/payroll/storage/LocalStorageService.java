package com.octopyder.payroll.storage;

import com.octopyder.payroll.common.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class LocalStorageService implements StorageService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final List<String> ALLOWED_EXTENSIONS = List.of("pdf", "png", "jpg", "jpeg");

    private final Path rootDir;

    public LocalStorageService(@Value("${app.storage.local-dir:./uploads}") String localDir) {
        this.rootDir = Paths.get(localDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage directory", e);
        }
    }

    @Override
    public String store(String originalFilename, InputStream content, String contentType, long sizeBytes) {
        if (sizeBytes > MAX_FILE_SIZE) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_FILE_TOO_LARGE", "File size exceeds 5 MB limit");
        }

        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_EXTENSION", "File extension is not allowed. Allowed: pdf, png, jpg, jpeg");
        }

        try {
            byte[] fileBytes = content.readAllBytes();
            if (fileBytes.length > MAX_FILE_SIZE) {
                throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_FILE_TOO_LARGE", "File size exceeds 5 MB limit");
            }

            validateMagicBytes(fileBytes, extension);

            String storageKey = UUID.randomUUID() + "." + extension;
            Path target = rootDir.resolve(storageKey);
            Files.write(target, fileBytes);

            return storageKey;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Failed to store file: " + e.getMessage());
        }
    }

    @Override
    public InputStream load(String storageKey) {
        try {
            Path target = rootDir.resolve(storageKey).normalize();
            if (!target.startsWith(rootDir) || !Files.exists(target)) {
                throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "File not found");
            }
            return Files.newInputStream(target);
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Failed to read file");
        }
    }

    @Override
    public String getDownloadUrl(String storageKey, Duration expiry) {
        return "/api/v1/documents/stream/" + storageKey;
    }

    @Override
    public void delete(String storageKey) {
        try {
            Path target = rootDir.resolve(storageKey).normalize();
            if (target.startsWith(rootDir)) {
                Files.deleteIfExists(target);
            }
        } catch (IOException ignored) {
        }
    }

    private void validateMagicBytes(byte[] bytes, String extension) {
        if (bytes == null || bytes.length < 4) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE", "File is empty or corrupted");
        }

        // Executable magic byte check: 'MZ' (DOS/PE) or ELF
        if ((bytes[0] == 0x4D && bytes[1] == 0x5A) || (bytes[0] == 0x7F && bytes[1] == 0x45 && bytes[2] == 0x4C && bytes[3] == 0x46)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE", "File content does not match allowed types");
        }

        boolean valid = switch (extension) {
            case "pdf" -> bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46; // %PDF
            case "png" -> bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                       && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
            case "jpg", "jpeg" -> (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
            default -> false;
        };

        if (!valid) {
            throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_INVALID_FILE", "File content does not match allowed types");
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}
