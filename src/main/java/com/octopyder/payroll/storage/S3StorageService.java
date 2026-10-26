package com.octopyder.payroll.storage;

import com.octopyder.payroll.common.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "storage.s3.bucket")
@Primary
public class S3StorageService implements StorageService {

    private final String bucket;
    private final S3Client s3Client;
    private final S3Presigner presigner;

    public S3StorageService(
        @Value("${STORAGE_BUCKET:${storage.s3.bucket:}}") String bucket,
        @Value("${STORAGE_REGION:${storage.s3.region:ap-south-1}}") String region,
        @Value("${STORAGE_ENDPOINT:${storage.s3.endpoint:}}") String endpoint,
        @Value("${STORAGE_ACCESS_KEY:${storage.s3.access-key:}}") String accessKey,
        @Value("${STORAGE_SECRET_KEY:${storage.s3.secret-key:}}") String secretKey
    ) {
        this.bucket = bucket;

        S3ClientBuilder clientBuilder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));

        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));

        if (endpoint != null && !endpoint.isBlank()) {
            clientBuilder.endpointOverride(URI.create(endpoint));
            presignerBuilder.endpointOverride(URI.create(endpoint));
        }

        this.s3Client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    @Override
    public String store(String originalFilename, InputStream content, String contentType, long sizeBytes) {
        String ext = originalFilename.contains(".") ? originalFilename.substring(originalFilename.lastIndexOf('.')) : "";
        String storageKey = UUID.randomUUID() + ext;

        try {
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .contentType(contentType)
                    .contentLength(sizeBytes)
                    .build(),
                RequestBody.fromInputStream(content, sizeBytes)
            );
            return storageKey;
        } catch (Exception e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "S3 store failed: " + e.getMessage());
        }
    }

    @Override
    public InputStream load(String storageKey) {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (Exception e) {
            throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "S3 object not found");
        }
    }

    @Override
    public String getDownloadUrl(String storageKey, Duration expiry) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(expiry != null ? expiry : Duration.ofSeconds(60))
            .getObjectRequest(b -> b.bucket(bucket).key(storageKey))
            .build();
        return presigner.presignGetObject(presignRequest).url().toString();
    }

    @Override
    public void delete(String storageKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
    }
}
