package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.ReportPdfStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** storage=minio 일 때 Spring 이 MinIO 저장소 빈을 실제로 만든다 — 생성자 둘 중 고를 쪽이 없으면 기동 실패했었다. */
class MinioReportPdfStorageWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MinioReportPdfStorageConfig.class, MinioReportPdfStorage.class,
                    FileSystemReportPdfStorage.class);

    @Test
    void minio_설정이면_MinIO_저장소_빈이_뜬다() {
        runner.withPropertyValues("aml.str.pdf.storage=minio",
                        "aml.str.pdf.access-key=minio", "aml.str.pdf.secret-key=minio-secret")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).getBean(ReportPdfStorage.class).isInstanceOf(MinioReportPdfStorage.class);
                });
    }
}
