package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.ReportPdfStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** MinIO의 S3 호환 API에 STR 확정본 PDF를 보관한다. */
@Component
@ConditionalOnProperty(prefix = "aml.str.pdf", name = "storage", havingValue = "minio")
public class MinioReportPdfStorage implements ReportPdfStorage {

    private final S3Client s3;
    private final String bucket;

    public MinioReportPdfStorage(
            S3Client s3,
            @Value("${aml.str.pdf.bucket:str-reports}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
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
        ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
        return response.asByteArray();
    }
}
