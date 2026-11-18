package com.team.aml.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 8080 GET /actuator/health 호환용 (03_shared §13, SCRUM-271) — 관리 포트를 8081 로 나눈 뒤에도 프론트 연결 확인이 그대로 돌게.
 * HealthEndpoint 상태만 {"status":…} 로, UP 이면 200 · 아니면 503. components·details 는 절대 안 나간다.
 */
class MainPortHealthControllerTest {

    private final HealthEndpoint endpoint = mock(HealthEndpoint.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MainPortHealthController(endpoint)).build();

    private MvcResult call() throws Exception {
        return mvc.perform(get("/actuator/health")).andReturn();
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_UP이면_200과_status만() throws Exception {
        when(endpoint.health()).thenReturn(Health.up().withDetail("database", "PostgreSQL").build());

        MvcResult res = call();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getContentAsString()).isEqualTo("{\"status\":\"UP\"}");
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_OUT_OF_SERVICE도_UP이_아니라서_503() throws Exception {
        when(endpoint.health()).thenReturn(Health.status(Status.OUT_OF_SERVICE).build());

        MvcResult res = call();

        assertThat(res.getResponse().getStatus()).isEqualTo(503);
        assertThat(res.getResponse().getContentAsString()).isEqualTo("{\"status\":\"OUT_OF_SERVICE\"}");
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_DOWN이면_503과_status만_details는_없다() throws Exception {
        when(endpoint.health()).thenReturn(Health.down()
                .withDetail("error", "Connection refused: db:5432").build());

        MvcResult res = call();

        String body = res.getResponse().getContentAsString();
        assertThat(res.getResponse().getStatus()).isEqualTo(503);
        assertThat(body).isEqualTo("{\"status\":\"DOWN\"}");
        assertThat(body).doesNotContain("components", "details", "Connection refused");
    }
}
