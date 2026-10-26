package com.octopyder.payroll.storage;

import com.octopyder.payroll.common.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageValidationTest {

    @TempDir
    Path tempDir;

    private LocalStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new LocalStorageService(tempDir.toString());
    }

    @Test
    void allowsValidPdfFile() {
        byte[] pdfBytes = "%PDF-1.7 Test PDF content".getBytes(StandardCharsets.US_ASCII);
        InputStream stream = new ByteArrayInputStream(pdfBytes);

        String key = storageService.store("doc.pdf", stream, "application/pdf", pdfBytes.length);
        assertThat(key).isNotNull();
        assertThat(storageService.load(key)).isNotNull();
    }

    @Test
    void allowsValidPngFile() {
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        InputStream stream = new ByteArrayInputStream(pngBytes);

        String key = storageService.store("image.png", stream, "image/png", pngBytes.length);
        assertThat(key).isNotNull();
    }

    @Test
    void rejectsFileWithExecutableMagicBytesEvenIfNamedPdf() {
        // 'MZ' magic bytes for DOS/Windows executable
        byte[] exeBytes = new byte[]{0x4D, 0x5A, 0x00, 0x00};
        InputStream stream = new ByteArrayInputStream(exeBytes);

        assertThatThrownBy(() -> storageService.store("fake.pdf", stream, "application/pdf", exeBytes.length))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("File content does not match allowed types");
    }

    @Test
    void rejectsDisallowedExtension() {
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        InputStream stream = new ByteArrayInputStream(bytes);

        assertThatThrownBy(() -> storageService.store("script.sh", stream, "application/x-sh", bytes.length))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("File extension is not allowed");
    }

    @Test
    void rejectsFileExceedingFiveMegabytes() {
        long fiveMbPlusOne = 5 * 1024 * 1024 + 1;
        InputStream stream = new ByteArrayInputStream(new byte[0]);

        assertThatThrownBy(() -> storageService.store("large.pdf", stream, "application/pdf", fiveMbPlusOne))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("File size exceeds 5 MB limit");
    }
}
