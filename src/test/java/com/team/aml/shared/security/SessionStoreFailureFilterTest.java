package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.metrics.ErrorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 503 으로 바꿀 때 지우는 헤더·남기는 헤더, 커밋 뒤 끊기 실패의 메시지 (#144 리뷰, SCRUM-273). */
class SessionStoreFailureFilterTest {

    private final ObjectMapper om = new ObjectMapper();
    private final SessionStoreFailureFilter filter =
            new SessionStoreFailureFilter(om, new ErrorMetrics(new SimpleMeterRegistry()));

    @Test
    void 정상_CORS와_X_Request_Id는_남기고_Set_Cookie는_지운다() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> {
            MockHttpServletResponse r = (MockHttpServletResponse) rs;
            r.setHeader(RequestIdFilter.HEADER, "req-1");
            r.setHeader("Access-Control-Allow-Origin", "http://localhost:5173");
            r.setHeader("Access-Control-Allow-Credentials", "true");
            r.addHeader("Vary", "Origin");
            r.addHeader("Set-Cookie", "SESSION=abc; HttpOnly");
            throw new RedisConnectionFailureException("Unable to connect to redis:6379");
        };

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/auth/me"), res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo("req-1");
        assertThat(res.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(res.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(res.getHeaders("Vary")).contains("Origin");
        assertThat(res.getHeaders("Set-Cookie")).isEmpty();
        JsonNode error = om.readTree(res.getContentAsString()).path("error");
        assertThat(error.path("message").asText()).isEqualTo(SessionStoreUnavailableException.MESSAGE);
        assertThat(error.path("details").isEmpty()).isTrue();
    }

    @Test
    void 정상_커밋_뒤_끊기만_실패하면_변경은_저장됐다고_알린다() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> {
            throw SessionStoreUnavailableException.afterCommit(new RedisConnectionFailureException("redis:6379"));
        };

        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/admin/users/42/deactivate"), res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        JsonNode error = om.readTree(res.getContentAsString()).path("error");
        assertThat(error.path("code").asText()).isEqualTo(SessionStoreUnavailableException.CODE);
        assertThat(error.path("message").asText()).isEqualTo(SessionStoreUnavailableException.MESSAGE_CHANGE_SAVED);
        assertThat(error.path("details").path("change_saved").asBoolean()).isTrue();
    }
}
