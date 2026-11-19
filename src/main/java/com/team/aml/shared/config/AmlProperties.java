package com.team.aml.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** application.yml 의 aml.* 를 타입으로. 비밀값은 환경변수에서 온다. */
@ConfigurationProperties(prefix = "aml")
public record AmlProperties(Ai ai, DevUsers devUsers, Cors cors, String datasetDir) {

    /**
     * @param timeoutSeconds    화면 요청 한 번의 전체 시간 한도(재시도 포함) — 점수 묶음·초안·검증·요약·health. 90
     * @param askTimeoutSeconds 질문 답변(`/v1/cases/ask`)만의 전체 시간 한도(재시도 포함, 규약 6.2). 30 (SCRUM-291)
     * @param askEnabled        질문 답변 켜기. 환경 변수 {@code AML_AI_ASK_ENABLED}, 기본 false — 읽는 쪽은 cases (SCRUM-291)
     * @param askPerMinute      질문하기(A8) 한 사람의 최근 60초 한도. 넘으면 429 QUESTION_RATE_LIMITED. 5 — 세는 쪽은 cases (SCRUM-302)
     */
    public record Ai(String baseUrl, String internalKey, int timeoutSeconds, int maxBatch, boolean mock,
                     @DefaultValue("30") int askTimeoutSeconds, @DefaultValue("false") boolean askEnabled,
                     @DefaultValue("5") int askPerMinute) {

        /** 0·음수면 첫 시도도 못 보내고 기록 없이 AI_TIMEOUT 만 나서 AI 장애처럼 보인다 → 기동 때 막는다. */
        public Ai {
            if (askTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("aml.ai.ask-timeout-seconds 는 1 이상이어야 함: " + askTimeoutSeconds);
            }
            if (askPerMinute <= 0) {   // 0 이면 아무도 못 묻는데 버튼은 보인다 → 기동 때 막는다
                throw new IllegalArgumentException("aml.ai.ask-per-minute 는 1 이상이어야 함: " + askPerMinute);
            }
        }
    }

    public record DevUsers(boolean enabled, String password) {}

    public record Cors(String allowedOrigin) {}
}
