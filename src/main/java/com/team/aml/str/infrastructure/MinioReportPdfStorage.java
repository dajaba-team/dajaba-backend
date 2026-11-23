package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.ReportPdfStorage;
import com.team.aml.shared.domain.ReportPdfStorageUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** MinIO의 S3 호환 API에 STR 확정본 PDF를 보관한다. */
@Component
@ConditionalOnProperty(prefix = "aml.str.pdf", name = "storage", havingValue = "minio")
public class MinioReportPdfStorage implements ReportPdfStorage {

    private final S3Client s3;
    private final String bucket;
    private final Path fallbackRoot;

    /** 생성자가 둘이라 Spring 이 쓸 쪽을 정해 둔다 — 없으면 기본 생성자를 찾다가 기동 실패(storage=minio). */
    @Autowired
    public MinioReportPdfStorage(
            S3Client s3,
            @Value("${aml.str.pdf.bucket:str-reports}") String bucket) {
        this(s3, bucket, null);
    }

    public MinioReportPdfStorage(
            S3Client s3,
            String bucket,
            Path fallbackRoot) {
        this.s3 = s3;
        this.bucket = bucket;
        this.fallbackRoot = fallbackRoot == null ? null : fallbackRoot.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/pdf")
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] get(String key) {
        try {
            ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return response.asByteArray();
        } catch (NoSuchKeyException e) {
            if (fallbackRoot != null) {
                Path localFile = fallbackRoot.resolve(key).normalize();
                if (localFile.startsWith(fallbackRoot) && !localFile.equals(fallbackRoot) && Files.isRegularFile(localFile)) {
                    try {
                        return Files.readAllBytes(localFile);
                    } catch (IOException ioe) {
                        throw new UncheckedIOException("로컬 전환 fallback PDF 읽기 실패: " + key, ioe);
                    }
                }
            }
            throw e;
        } catch (S3Exception e) {
            if (e.statusCode() >= 500) {
                throw new ReportPdfStorageUnavailableException("확정본 PDF 저장소 일시 오류: " + key, e);
            }
            throw e;
        } catch (SdkClientException e) {
            throw new ReportPdfStorageUnavailableException("확정본 PDF 저장소 연결 실패: " + key, e);
        }
    }
}