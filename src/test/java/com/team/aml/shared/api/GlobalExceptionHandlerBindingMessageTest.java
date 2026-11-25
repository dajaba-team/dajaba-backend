package com.team.aml.shared.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.team.aml.shared.metrics.ErrorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 11/25 전체 점검 QA-BE-01 — 쿼리 바인딩 실패(없는 enum 값·날짜 형식)의 details 값에 Spring 기본 메시지
 * ("Failed to convert property value of type 'java.lang.String' to required type 'com.team.aml…'")가 그대로 나가
 * Java 타입·패키지 이름이 보였다. 바인딩 실패는 고정 문구, 코드·400·details 키 모양은 그대로.
 * GET /cases 의 CaseListQuery 와 같은 모양(record + @ModelAttribute + @Valid)으로 본다.
 */
class GlobalExceptionHandlerBindingMessageTest {

    static final String FIXED = "형식이 올바르지 않습니다";

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new QueryController())
            .setControllerAdvice(new GlobalExceptionHandler(new ErrorMetrics(new SimpleMeterRegistry())))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)))
            .build();

    // --- 실패: 없는 enum 값 ---

    @Test
    void 실패_없는_enum_값은_400_INVALID_REQUEST이고_details는_고정_문구() throws Exception {
        mvc.perform(get("/q").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("요청 값이 올바르지 않습니다"))
                .andExpect(jsonPath("$.error.details.status").value(FIXED));
    }

    @Test
    void 실패_없는_enum_값_응답_본문에_Java_타입_패키지_변환_문구가_없다() throws Exception {
        mvc.perform(get("/q").param("status", "OPEN,BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("com.team"))))
                .andExpect(content().string(not(containsString("Failed to convert"))))
                .andExpect(content().string(not(containsString("BOGUS"))));
    }

    // --- 경계: 날짜 형식·숫자 형식도 같은 고정 문구, 여러 필드면 필드마다 ---

    @Test
    void 경계_날짜_형식이_틀리면_같은_고정_문구() throws Exception {
        mvc.perform(get("/q").param("from", "2024-13-40"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.from").value(FIXED))
                .andExpect(content().string(not(containsString("java."))));
    }

    @Test
    void 경계_숫자_자리에_글자면_같은_고정_문구이고_details_키는_snake_case() throws Exception {
        mvc.perform(get("/q").param("pageSize", "abc").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.status").value(FIXED))
                .andExpect(jsonPath("$.error.details.page_size").value(FIXED))
                .andExpect(content().string(not(containsString("java."))));
    }

    // --- 정상: 우리가 쓴 @Valid 위반 메시지는 그대로 ---

    @Test
    void 정상_Valid_위반은_원래_메시지_그대로() throws Exception {
        mvc.perform(get("/q").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.page_size").value("100 이하로 보내 주세요"));
    }

    @Test
    void 정상_올바른_값이면_200() throws Exception {
        mvc.perform(get("/q").param("status", "OPEN,CLOSED").param("from", "2024-05-01").param("pageSize", "20"))
                .andExpect(status().isOk());
    }

    enum Status { OPEN, CLOSED }

    record Query(List<Status> status,
                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                 @Min(value = 1, message = "1 이상으로 보내 주세요")
                 @Max(value = 100, message = "100 이하로 보내 주세요") Integer pageSize) {
    }

    @RestController
    static class QueryController {
        @GetMapping("/q")
        void list(@Valid @ModelAttribute Query query) {
        }
    }
}
