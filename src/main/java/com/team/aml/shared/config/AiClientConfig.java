package com.team.aml.shared.config;

import io.netty.channel.ChannelOption;
import io.netty.resolver.AddressResolverGroup;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * 실제 AI 서버용 WebClient (aml.ai.mock=false). 여기서는 baseUrl · 연결 타임아웃 · 버퍼 크기 · 이름 조회 기억 · 연결 풀만 둔다.
 * 응답 타임아웃(전체 90초)·재시도·헤더는 WebClientAiClient 가 호출마다 건다 (SCRUM-17 D1).
 *
 * <p>이름 조회 기억·전용 풀 (SCRUM-255): 기본 이름 조회기(Netty DNS)는 기억 상한이 없어 DNS 응답 TTL(compose 내장 DNS 600초)만큼
 * 예전 IP를 쓰고, 공용 풀은 놀고 있는 연결 수명 제한이 없다. 그래서 상한 5초 · AI 전용 풀 수명 4초(AI uvicorn keep-alive 5초보다 짧게)를 건다.
 * 전용 풀의 나머지 값은 공용 풀(HttpResources)과 같게 둔다.
 */
@Configuration
@ConditionalOnProperty(prefix = "aml.ai", name = "mock", havingValue = "false")
public class AiClientConfig implements DisposableBean {

    private static final int CONNECT_TIMEOUT_MS = 3000;           // 연결 시도 한도(연결 타임아웃 → 재시도 안 함)
    private static final int MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;  // 500건 점수 응답 대비
    static final Duration DNS_CACHE_MAX_TTL = Duration.ofSeconds(5);   // 이름 조회 기억 상한 — AI 컨테이너를 다시 만들어도 몇 초 안에 새 IP로
    static final Duration POOL_MAX_IDLE_TIME = Duration.ofSeconds(4);  // 놀고 있는 연결 수명 — AI uvicorn 기본 keep-alive 5초보다 짧게
    static final int POOL_MAX_CONNECTIONS = 500;                       // 공용 풀(HttpResources)과 같은 값
    private static final String POOL_NAME = "ai";

    private final ConnectionProvider connectionProvider = aiConnectionProvider(POOL_MAX_IDLE_TIME);
    private AddressResolverGroup<?> resolverGroup;

    @Bean
    WebClient aiWebClient(AmlProperties props) {
        HttpClient httpClient = aiHttpClient(connectionProvider);
        resolverGroup = httpClient.configuration().resolver();
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
        return WebClient.builder()
                .baseUrl(props.ai().baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    /** AI 전용 풀. 최대 연결 · 연결 대기 시간은 공용 풀 기본값(ConnectionProvider.create("http", 500))과 같고 놀고 있는 연결 수명만 더한다. */
    static ConnectionProvider aiConnectionProvider(Duration maxIdleTime) {
        return ConnectionProvider.builder(POOL_NAME)
                .maxConnections(POOL_MAX_CONNECTIONS)
                .pendingAcquireTimeout(Duration.ofMillis(ConnectionProvider.DEFAULT_POOL_ACQUIRE_TIMEOUT))
                .maxIdleTime(maxIdleTime)
                .build();
    }

    static HttpClient aiHttpClient(ConnectionProvider provider) {
        return HttpClient.create(provider)
                .resolver(spec -> spec.cacheMaxTimeToLive(DNS_CACHE_MAX_TTL))  // 최소·실패 기억은 기본 0초 그대로
                .disableRetry(true)      // 재시도는 WebClientAiClient 가 전담 — Netty 기본 1회 재시도를 끈다
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS);
    }

    /** 앱 종료 때 전용 풀과 이름 조회기를 닫는다 — 둘 다 공용 자원이 아니라 라이브러리가 대신 닫아 주지 않는다. */
    @Override
    public void destroy() {
        connectionProvider.dispose();
        if (resolverGroup != null) {
            resolverGroup.close();
        }
    }
}
