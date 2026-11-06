package com.team.aml.shared.api;

import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.domain.ForbiddenException;
import com.team.aml.shared.domain.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예외 → HTTP 변환 (레퍼런스 8.2, 03_shared §2). 시큐리티·스프링 컨텍스트 없이 핸들러만 붙여 본다 (SCRUM-67 ★A 1).
 * ForbiddenException 하위는 403, 그냥 DomainException 하위는 409, NotFoundException 하위는 404.
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 정상_ForbiddenException_하위는_403과_클래스_이름_코드로_나간다() throws Exception {
        mvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SOMETHING_FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("승인자 역할이 아닙니다"))
                .andExpect(jsonPath("$.error.details.draft_id").value(7))
                .andExpect(jsonPath("$.error.details.role").value("INVESTIGATOR"));
    }

    @Test
    void 경계_details_없이_던지면_403에_빈_details() throws Exception {
        mvc.perform(get("/test/forbidden-no-details"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SOMETHING_FORBIDDEN"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    void 회귀_그냥_DomainException_하위는_409_그대로() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOMETHING_CONFLICT"))
                .andExpect(jsonPath("$.error.details.case_no").value("CASE-2022-000057"));
    }

    @Test
    void 회귀_NotFoundException_하위는_404_그대로() throws Exception {
        mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("THING_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.id").value(42));
    }

    // --- 없는 경로·안 되는 메서드 (SCRUM-157, develop 에서는 500) ---

    @Test
    void 정상_NoResourceFoundException은_404_NOT_FOUND와_빈_details() throws Exception {
        mvc.perform(get("/test/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("요청한 주소가 없습니다"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    void 정상_안_되는_메서드는_405_METHOD_NOT_ALLOWED와_Allow_헤더() throws Exception {
        mvc.perform(delete("/test/not-found"))   // GET 만 있는 경로
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.message").value("지원하지 않는 요청 방식입니다"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
    }

    @Test
    void 경계_허용_메서드를_모르면_Allow_헤더_없이_405() throws Exception {
        mvc.perform(get("/test/method-unknown"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().doesNotExist(HttpHeaders.ALLOW));
    }

    @Test
    void 회귀_처리_안_된_예외는_500_INTERNAL_ERROR_그대로() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("알 수 없는 오류"));
    }

    // --- 테스트 전용 예외·컨트롤러 (테스트 소스에만 있음) ---

    static class SomethingForbiddenException extends ForbiddenException {
        SomethingForbiddenException(String message) { super(message); }
        SomethingForbiddenException(String message, Map<String, Object> details) { super(message, details); }
    }

    static class SomethingConflictException extends DomainException {
        SomethingConflictException(String message, Map<String, Object> details) { super(message, details); }
    }

    static class ThingNotFoundException extends NotFoundException {
        ThingNotFoundException(String message, Map<String, Object> details) { super(message, details); }
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/forbidden")
        void forbidden() {
            throw new SomethingForbiddenException("승인자 역할이 아닙니다",
                    Map.of("draft_id", 7, "role", "INVESTIGATOR"));
        }

        @GetMapping("/test/forbidden-no-details")
        void forbiddenNoDetails() {
            throw new SomethingForbiddenException("담당자가 아닙니다");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new SomethingConflictException("상태 충돌", Map.of("case_no", "CASE-2022-000057"));
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new ThingNotFoundException("없음", Map.of("id", 42));
        }

        /** 실제 앱에서는 정적 리소스 핸들러(/**)가 없는 경로에서 던진다. standalone 엔 그 핸들러가 없어 직접 던진다. */
        @GetMapping("/test/no-resource")
        void noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "test/no-resource");
        }

        /** 허용 메서드 목록 없이 만든 예외 — getSupportedHttpMethods() 가 null. */
        @GetMapping("/test/method-unknown")
        void methodUnknown() throws HttpRequestMethodNotSupportedException {
            throw new HttpRequestMethodNotSupportedException("PATCH");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("boom");
        }
    }
}
