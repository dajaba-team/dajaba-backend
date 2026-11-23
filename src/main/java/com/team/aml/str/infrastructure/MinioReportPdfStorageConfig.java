package com.team.aml.str.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;

/** MinIO 사용 시에만 S3 클라이언트를 만든다. 클라이언트 생성 시 원격 연결은 하지 않는다. */
@Configuration
@ConditionalOnProperty(prefix = "aml.str.pdf", name = "storage", havingValue = "minio")
public class MinioReportPdfStorageConfig {

    @Bean(destroyMethod = "close")
    S3Client strPdfS3Client(
            @Value("${aml.str.pdf.endpoint:http://localhost:9000}") String endpoint,
            @Value("${aml.str.pdf.region:ap-northeast-2}") String region,
            @Value("${aml.str.pdf.access-key:}") String accessKey,
            @Value("${aml.str.pdf.secret-key:}") String secretKey,
            @Value("${aml.str.pdf.timeout-seconds:10}") int timeoutSeconds) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("aml.str.pdf.storage=minio 설정 시 access-key와 secret-key는 비어 있을 수 없습니다");
        }
        Duration callTimeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(callTimeout)
                        .apiCallAttemptTimeout(callTimeout)
                        .build())
                .build();
    }
}