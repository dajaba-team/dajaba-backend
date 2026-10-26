package com.team.aml.shared.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 기동 시 AI 서버 health 1회. 실패해도 기동은 한다 (레퍼런스 8.5). */
@Component
public class AiHealthCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiHealthCheck.class);

    private final ObjectProvider<AiClient> aiClients;

    public AiHealthCheck(ObjectProvider<AiClient> aiClients) {
        this.aiClients = aiClients;
    }

    @Override
    public void run(ApplicationArguments args) {
        AiClient aiClient = aiClients.getIfAvailable();
        if (aiClient == null) {
            log.warn("AiClient 빈이 없습니다 — aml.ai.mock=false 인데 WebClientAiClient 가 아직 없음(W3). AI 호출 유스케이스는 기동 후 실패합니다");
            return;
        }
        try {
            var h = aiClient.health();
            log.info("AI server health: status={} model={} generator={} ({})", h.status(), h.modelVersion(),
                    h.generatorVersion(), aiClient.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("AI server health check failed — 기동은 계속합니다: {}", e.getMessage());
        }
    }
}
