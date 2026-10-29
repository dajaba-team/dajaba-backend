package com.team.aml.shared.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * 실제 AI 서버용 WebClient (aml.ai.mock=false). 여기서는 baseUrl · 연결 타임아웃 · 버퍼 크기만 둔다.
 * 응답 타임아웃(전체 90초)·재시도·헤더는 WebClientAiClient 가 호출마다 건다 (SCRUM-17 D1).
 */
@Configuration
@ConditionalOnProperty(prefix = "aml.ai", name = "mock", havingValue = "false")
public class AiClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 3000;           // 연결 시도 한도(연결 타임아웃 → 재시도 안 함)
    private static final int MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;  // 500건 점수 응답 대비

    @Bean
    WebClient aiWebClient(AmlProperties props) {
        HttpClient httpClient = HttpClient.create()
                .disableRetry(true)      // 재시도는 WebClientAiClient 가 전담 — Netty 기본 1회 재시도를 끈다 (codex 지적)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
        return WebClient.builder()
                .baseUrl(props.ai().baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
