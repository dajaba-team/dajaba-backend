package com.team.aml.shared.api;

import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.domain.ForbiddenException;
import com.team.aml.shared.domain.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    }
}
