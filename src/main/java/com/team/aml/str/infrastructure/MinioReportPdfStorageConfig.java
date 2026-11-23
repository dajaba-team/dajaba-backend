package com.team.aml.str.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/** MinIO 사용 시에만 S3 클라이언트를 만든다. 클라이언트 생성 시 원격 연결은 하지 않는다. */
@Configuration
@ConditionalOnProperty(prefix = "aml.str.pdf", name = "storage", havingValue = "minio")
public class MinioReportPdfStorageConfig {

    @Bean(destroyMethod = "close")
    S3Client strPdfS3Client(
            @Value("${aml.str.pdf.endpoint:http://localhost:9000}") String endpoint,
            @Value("${aml.str.pdf.region:ap-northeast-2}") String region,
            @Value("${aml.str.pdf.access-key:}") String accessKey,
            @Value("${aml.str.pdf.secret-key:}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
