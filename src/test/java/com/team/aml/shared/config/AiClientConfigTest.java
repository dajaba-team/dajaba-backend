package com.team.aml.shared.config;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;
import reactor.netty.http.HttpResources;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.NameResolverProvider;

import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * AiClientConfig — AI 서버 이름 조회 기억 상한·AI 전용 연결 풀 (SCRUM-255).
 * 실제 DNS 기억 효과(컨테이너 재생성 뒤 몇 초 안에 따라감)는 단위 테스트로 재현하기 어려워 compose 실제 확인으로 본다.
 */
class AiClientConfigTest {

    private static final Duration UVICORN_DEFAULT_KEEP_ALIVE = Duration.ofSeconds(5);
    private static final Duration SHORT_IDLE = Duration.ofMillis(200);

    private MockWebServer server;
    private ConnectionProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (provider != null) {
            provider.disposeLater().block(Duration.ofSeconds(5));
        }
        server.shutdown();
    }

    @Test
    void 정상_이름_조회_기억은_최대_5초이고_최소와_실패_기억은_0초다() {
        provider = AiClientConfig.aiConnectionProvider(AiClientConfig.POOL_MAX_IDLE_TIME);
        HttpClient client = AiClientConfig.aiHttpClient(provider);

        NameResolverProvider resolver = client.configuration().getNameResolverProvider();
        assertThat(resolver).as("이름 조회 설정이 HttpClient 에 들어가야 함").isNotNull();
        assertThat(AiClientConfig.DNS_CACHE_MAX_TTL).isEqualTo(Duration.ofSeconds(5));
        assertThat(resolver.cacheMaxTimeToLive()).isEqualTo(AiClientConfig.DNS_CACHE_MAX_TTL);
        assertThat(resolver.cacheMinTimeToLive()).isEqualTo(Duration.ZERO);
        assertThat(resolver.cacheNegativeTimeToLive()).isEqualTo(Duration.ZERO);
    }

    @Test
    void 정상_AI_전용_풀의_최대_연결_수는_공용_풀과_같은_500이다() {
        provider = AiClientConfig.aiConnectionProvider(AiClientConfig.POOL_MAX_IDLE_TIME);

        assertThat(provider.maxConnections()).isEqualTo(AiClientConfig.POOL_MAX_CONNECTIONS).isEqualTo(500);
        assertThat(provider.maxConnections()).isEqualTo(HttpResources.get().maxConnections());
    }

    @Test
    void 경계_놀고_있는_연결_수명은_AI_uvicorn_기본_keep_alive_5초보다_짧다() {
        assertThat(AiClientConfig.POOL_MAX_IDLE_TIME).isEqualTo(Duration.ofSeconds(4));
        assertThat(AiClientConfig.POOL_MAX_IDLE_TIME).isLessThan(UVICORN_DEFAULT_KEEP_ALIVE);
    }

    @Test
    void 정상_수명보다_오래_놀던_연결은_다시_쓰지_않고_새로_연결한다() throws Exception {
        provider = AiClientConfig.aiConnectionProvider(SHORT_IDLE);
        HttpClient client = AiClientConfig.aiHttpClient(provider);
        server.enqueue(new MockResponse().setBody("1"));
        server.enqueue(new MockResponse().setBody("2"));

        get(client);
        Thread.sleep(SHORT_IDLE.toMillis() * 3);
        get(client);

        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getSequenceNumber()).isZero();
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getSequenceNumber())
                .as("두 번째 요청은 새 연결(연결 안 순번 0)이어야 함").isZero();
    }

    @Test
    void 경계_수명보다_오래_걸리는_응답도_끊지_않는다() throws Exception {
        provider = AiClientConfig.aiConnectionProvider(SHORT_IDLE);
        HttpClient client = AiClientConfig.aiHttpClient(provider);
        server.enqueue(new MockResponse().setBody("slow").setHeadersDelay(1, TimeUnit.SECONDS));
        server.enqueue(new MockResponse().setBody("next"));

        assertThat(get(client)).isEqualTo("slow");
        assertThat(get(client)).isEqualTo("next");

        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getSequenceNumber()).isZero();
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getSequenceNumber())
                .as("수명은 놀고 있는 연결에만 걸림 — 1초 응답 동안 쓰던 연결이 그대로 남아 바로 다시 씀").isEqualTo(1);
    }

    @Test
    void 대조_수명_안이면_같은_연결을_다시_쓴다() throws Exception {
        provider = AiClientConfig.aiConnectionProvider(AiClientConfig.POOL_MAX_IDLE_TIME);
        HttpClient client = AiClientConfig.aiHttpClient(provider);
        server.enqueue(new MockResponse().setBody("1"));
        server.enqueue(new MockResponse().setBody("2"));

        get(client);
        get(client);

        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getSequenceNumber()).isZero();
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getSequenceNumber())
                .as("두 번째 요청은 같은 연결(연결 안 순번 1)").isEqualTo(1);
    }

    @Test
    void 회귀_설정한_클라이언트로_없는_이름을_부르면_그대로_UnknownHostException이다() {
        provider = AiClientConfig.aiConnectionProvider(AiClientConfig.POOL_MAX_IDLE_TIME);
        HttpClient client = AiClientConfig.aiHttpClient(provider);

        Throwable e = catchThrowable(() -> client.get().uri("http://ai.invalid/v1/health")
                .responseSingle((r, b) -> b.asString())
                .block(Duration.ofSeconds(10)));

        assertThat(Exceptions.unwrap(e)).as("#118 분류(연결 단계 실패 → AI_UNAVAILABLE·재시도 1회)가 기대하는 원인")
                .isInstanceOf(UnknownHostException.class);
    }

    private String get(HttpClient client) {
        return client.get().uri(server.url("/v1/health").toString())
                .responseSingle((r, b) -> b.asString())
                .block(Duration.ofSeconds(5));
    }
}
