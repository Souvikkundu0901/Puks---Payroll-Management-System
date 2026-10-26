package com.octopyder.payroll.storage;

import java.io.InputStream;
import java.time.Duration;

public interface StorageService {

    String store(String originalFilename, InputStream content, String contentType, long sizeBytes);

    InputStream load(String storageKey);

    String getDownloadUrl(String storageKey, Duration expiry);

    void delete(String storageKey);
}
