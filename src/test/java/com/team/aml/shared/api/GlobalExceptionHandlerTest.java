package com.team.aml.shared.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.domain.ForbiddenException;
import com.team.aml.shared.domain.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예외 → HTTP 변환 (레퍼런스 8.2, 03_shared §2). 시큐리티·스프링 컨텍스트 없이 핸들러만 붙여 본다 (SCRUM-67 ★A 1).
 * ForbiddenException 하위는 403, 그냥 DomainException 하위는 409, NotFoundException 하위는 404.
 */
class GlobalExceptionHandlerTest {

    /** standalone 은 application.yml 의 Jackson 설정을 안 읽는다 — 요청 본문을 snake_case 로 읽도록 같은 전략을 넣는다. */
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)))
            .build();

    // --- 400 INVALID_REQUEST details 키 = 요청 JSON 이름 (SCRUM-167, IF-COM-03) ---

    @Test
    void 정상_Valid_실패_details_키는_요청_JSON_이름_snake_case() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"version\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.investigator_notes").value("메모는 필수입니다"))
                .andExpect(jsonPath("$.error.details.investigatorNotes").doesNotExist());
    }

    @Test
    void 정상_중첩_목록은_조각마다_snake_case이고_인덱스는_그대로() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"investigator_notes\": \"x\", \"version\": 0, \"items\": [{\"transaction_id\": 0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details['items[0].transaction_id']").value("거래 번호는 양수입니다"))
                .andExpect(jsonPath("$.error.details['items[0].transactionId']").doesNotExist());
    }

    @Test
    void 회귀_Valid_실패_코드와_메시지는_그대로() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"version\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("요청 값이 올바르지 않습니다"));
    }

    @Test
    void 경계_한_단어_필드는_이름_그대로() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"investigator_notes\": \"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.version").value("version 은 필수입니다"));
    }

    /** 위반 순서는 Bean Validation 이 정하지 않으므로 순서를 정한 BindingResult 로 핸들러를 바로 부른다. */
    @Test
    void 경계_같은_필드_위반이_여럿이면_첫_번째만() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "body");
        result.addError(new FieldError("body", "investigatorNotes", "첫 번째"));
        result.addError(new FieldError("body", "investigatorNotes", "두 번째"));
        MethodParameter param = new MethodParameter(
                ThrowingController.class.getDeclaredMethod("body", MemoBody.class), 0);

        ErrorResponse body = new GlobalExceptionHandler()
                .invalid(new MethodArgumentNotValidException(param, result)).getBody();

        assertThat(body.error().details()).containsExactly(Map.entry("investigator_notes", "첫 번째"));
    }

    @Test
    void 정상_RequestParam_이름이_있으면_details_parameter는_그_이름() throws Exception {
        mvc.perform(get("/test/param").param("target_id", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.parameter").value("target_id"));
    }

    @Test
    void 경계_RequestParam_이름이_없으면_변수_이름() throws Exception {
        mvc.perform(get("/test/param").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.parameter").value("page"));
    }

    @Test
    void 회귀_타입이_틀린_쿼리는_details_parameter_그대로() throws Exception {
        mvc.perform(get("/test/param").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("요청 파라미터가 올바르지 않습니다"))
                .andExpect(jsonPath("$.error.details.parameter").value("page"));
    }

    @Test
    void 회귀_빠진_필수_쿼리는_details_parameter_그대로() throws Exception {
        mvc.perform(get("/test/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.parameter").value("as_of"));
    }

    @Test
    void 실패_JSON_파싱_실패는_본문_메시지_그대로() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("요청 본문을 읽을 수 없습니다"))
                .andExpect(jsonPath("$.error.details").isEmpty());
    }

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

    record MemoBody(@NotNull(message = "메모는 필수입니다") String investigatorNotes,
                    @NotNull(message = "version 은 필수입니다") Long version,
                    List<@Valid Item> items) {
    }

    record Item(@Positive(message = "거래 번호는 양수입니다") Long transactionId) {
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/test/body")
        void body(@Valid @RequestBody MemoBody body) {
        }

        @GetMapping("/test/param")
        void param(@RequestParam(name = "target_id", required = false) @Min(1) Long targetId,
                   @RequestParam(required = false) @Min(0) Integer page) {
        }

        @GetMapping("/test/required")
        void required(@RequestParam("as_of") String asOf) {
        }

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
