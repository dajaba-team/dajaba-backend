package com.team.aml.str.infrastructure;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioReportPdfStorageTest {

    private final S3Client s3 = mock(S3Client.class);
    private final MinioReportPdfStorage storage = new MinioReportPdfStorage(s3, "str-reports");

    @Test
    void 정상_PDF를_str_reports_버킷의_키로_업로드한다() {
        byte[] pdf = "%PDF-test".getBytes();

        storage.put("2026/STR-2026-000001-1.pdf", pdf);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("str-reports");
        assertThat(request.getValue().key()).isEqualTo("2026/STR-2026-000001-1.pdf");
        assertThat(request.getValue().contentType()).isEqualTo("application/pdf");
    }

    @Test
    void 정상_버킷에서_PDF_바이트를_읽는다() {
        byte[] pdf = "%PDF-test".getBytes();
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), pdf));

        assertThat(storage.get("2026/STR-2026-000001-1.pdf")).isEqualTo(pdf);
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        org.mockito.Mockito.verify(s3).getObjectAsBytes(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("str-reports");
        assertThat(request.getValue().key()).isEqualTo("2026/STR-2026-000001-1.pdf");
    }

    @Test
    void 실패_MinIO_연결이_끊기면_SDK_예외를_호출자에게_전달한다() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("MinIO unavailable"));

        assertThatThrownBy(() -> storage.put("2026/STR-2026-000001-1.pdf", new byte[]{1}))
                .isInstanceOf(SdkClientException.class);
    }
}
