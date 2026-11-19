package com.team.aml.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** application.yml 의 aml.* 를 타입으로. 비밀값은 환경변수에서 온다. */
@ConfigurationProperties(prefix = "aml")
public record AmlProperties(Ai ai, DevUsers devUsers, Cors cors, String datasetDir) {

    /**
     * @param timeoutSeconds    화면 요청 한 번의 전체 시간 한도(재시도 포함) — 점수 묶음·초안·검증·요약·health. 90, 1 이상 (SCRUM-321)
     * @param maxBatch          점수 묶음 크기. 500, 1~500 (SCRUM-321)
     * @param askTimeoutSeconds 질문 답변(`/v1/cases/ask`)만의 전체 시간 한도(재시도 포함, 규약 6.2). 30 (SCRUM-291)
     * @param askEnabled        질문 답변 켜기. 환경 변수 {@code AML_AI_ASK_ENABLED}, 기본 false — 읽는 쪽은 cases (SCRUM-291)
     */
    public record Ai(String baseUrl, String internalKey, @DefaultValue("90") int timeoutSeconds,
                     @DefaultValue("500") int maxBatch, boolean mock,
                     @DefaultValue("30") int askTimeoutSeconds, @DefaultValue("false") boolean askEnabled) {

        /** 규약 6.2 점수 묶음 상한. */
        private static final int MAX_BATCH_LIMIT = 500;

        /** 0·음수면 첫 시도도 못 보내고 기록 없이 AI_TIMEOUT 만 나서 AI 장애처럼 보인다 → 기동 때 막는다. */
        public Ai {
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("aml.ai.timeout-seconds 는 1 이상이어야 함: " + timeoutSeconds);
            }
            if (maxBatch < 1 || maxBatch > MAX_BATCH_LIMIT) {
                throw new IllegalArgumentException("aml.ai.max-batch 는 1 이상 500 이하여야 함(규약 6.2): " + maxBatch);
            }
            if (askTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("aml.ai.ask-timeout-seconds 는 1 이상이어야 함: " + askTimeoutSeconds);
            }
        }
    }

    public record DevUsers(boolean enabled, String password) {}

    public record Cors(String allowedOrigin) {}
}
