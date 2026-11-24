package com.team.aml.shared.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.ReportPdfNotReadyException;
import com.team.aml.str.domain.ReportPdfStatus;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Accept: application/pdf 하나만 보낸 요청의 오류 응답 (QnA BH-BE-01) — 실제 필터 체인 + GlobalExceptionHandler.
 *
 * <p>S7·S8 처럼 PDF 를 돌려주는 핸들러를 흉내 낸 테스트용 컨트롤러가 S8 과 같은 도메인 예외를 던진다.
 * 고치기 전에는 JSON 본문을 못 써서(HttpMediaTypeNotAcceptableException) 원래 409·404·403 이 사라졌다 — 실제 서버에선 /error 로 넘어가 403.
 * 고친 뒤: Accept 와 상관없이 원래 상태 코드 + application/json 에러 본문, 성공(200 PDF)은 그대로.
 * 마지막 둘은 안전장치(SecurityConfig ERROR 디스패치 통과)를 본다.
 */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + PdfAcceptErrorResponseTest.PASSWORD})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PdfAcceptErrorResponseTest.PdfProbe.class)
class PdfAcceptErrorResponseTest {

    static final String PASSWORD = "test-pdf-accept-1234";
    static final byte[] PDF = "%PDF-1.7 probe".getBytes(StandardCharsets.US_ASCII);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    /** S8 이 던지는 예외를 그대로 던지는 PDF 핸들러. 성공은 S7·S8 처럼 produces 없이 Content-Type 을 직접 정한다. src/test 에만 있다. */
    @TestConfiguration
    @RestController
    static class PdfProbe {
        @GetMapping("/api/v1/_pdf-probe/{kind}.pdf")
        ResponseEntity<byte[]> pdf(@PathVariable String kind) {
            return switch (kind) {
                case "pending" -> throw new ReportPdfNotReadyException(ReportPdfStatus.PENDING);
                case "failed" -> throw new ReportPdfNotReadyException(ReportPdfStatus.FAILED);
                case "wrong-state" -> throw InvalidCaseTransitionException.notEditable(CaseStatus.REJECTED, null, "DOWNLOAD_PDF");
                case "not-assignee" -> throw new NotAssigneeException("CASE-2022-000001");
                case "missing" -> throw new DraftNotFoundException("DRF-999999");
                default -> ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(PDF);
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    // 1
    @Test
    void 정상_PDF가_만드는_중이면_PDF만_받는_요청에도_409_REPORT_PDF_NOT_READY_PENDING_JSON() throws Exception {
        MvcResult r = pdfOnly("pending");

        assertJsonError(r, 409, "REPORT_PDF_NOT_READY");
        assertThat(json(r).path("error").path("details").path("pdf_status").asText()).isEqualTo("PENDING");
    }

    // 2
    @Test
    void 정상_PDF를_못_만들었으면_409_REPORT_PDF_NOT_READY_FAILED() throws Exception {
        MvcResult r = pdfOnly("failed");

        assertJsonError(r, 409, "REPORT_PDF_NOT_READY");
        assertThat(json(r).path("error").path("details").path("pdf_status").asText()).isEqualTo("FAILED");
    }

    // 3
    @Test
    void 정상_상태가_안_맞으면_409_INVALID_CASE_TRANSITION() throws Exception {   // QnA 재현: 반려된 초안에 S8
        MvcResult r = pdfOnly("wrong-state");

        assertJsonError(r, 409, "INVALID_CASE_TRANSITION");
        assertThat(json(r).path("error").path("details").path("action").asText()).isEqualTo("DOWNLOAD_PDF");
    }

    // 4
    @Test
    void 정상_권한이_없으면_403_NOT_ASSIGNEE_그대로() throws Exception {   // 403 이어도 "권한 거부"가 맞는 코드로
        assertJsonError(pdfOnly("not-assignee"), 403, "NOT_ASSIGNEE");
    }

    // 5
    @Test
    void 정상_없는_초안이면_404_DRAFT_NOT_FOUND() throws Exception {
        assertJsonError(pdfOnly("missing"), 404, "DRAFT_NOT_FOUND");
    }

    // 6
    @Test
    void 정상_성공은_그대로_200_application_pdf() throws Exception {
        MvcResult r = pdfOnly("ok");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(PDF);
    }

    // 7
    @Test
    void 정상_JSON을_받는_요청도_전처럼_409_JSON() throws Exception {   // 프론트(axios 기본 Accept) 경로가 안 바뀌었는지
        MvcResult r = mvc.perform(get("/api/v1/_pdf-probe/pending.pdf").session(login())
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL)).andReturn();

        assertJsonError(r, 409, "REPORT_PDF_NOT_READY");
    }

    // 8
    @Test
    void 안전장치_컨테이너가_오류를_error로_다시_보낸_요청은_막지_않는다() throws Exception {
        MvcResult r = mvc.perform(get("/error").with(req -> {
            req.setDispatcherType(DispatcherType.ERROR);                 // 실제 서버에서 원래 요청이 실패했을 때와 같은 디스패치
            req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 409);
            req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/str-drafts/DRF-000001/report.pdf");
            return req;
        })).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(409);          // 고치기 전: denyAll 에 걸려 401·403
    }

    // 9
    @Test
    void 안전장치_error를_주소로_직접_부르는_건_그대로_막힌다() throws Exception {
        MvcResult r = mvc.perform(get("/error").session(login())).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);          // REQUEST 디스패치는 마지막 줄 denyAll
    }

    // ---- 도우미 ----

    private MvcResult pdfOnly(String kind) throws Exception {
        return mvc.perform(get("/api/v1/_pdf-probe/" + kind + ".pdf").session(login())
                .accept(MediaType.APPLICATION_PDF)).andReturn();
    }

    private void assertJsonError(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResolvedException()).isNotNull();
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(r.getResponse().getContentType()).isCompatibleWith(MediaType.APPLICATION_JSON))
                .as("Content-Type %s", r.getResponse().getContentType()).isTrue();
        assertThat(json(r).path("error").path("code").asText()).isEqualTo(code);
    }

    private MockHttpSession login() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("login_id", "apr01", "password", PASSWORD)))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
