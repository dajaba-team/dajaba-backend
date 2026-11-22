package com.team.aml.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** application.yml 의 aml.* 를 타입으로. 비밀값은 환경변수에서 온다. */
@ConfigurationProperties(prefix = "aml")
public record AmlProperties(Ai ai, DevUsers devUsers, Cors cors, String datasetDir) {

    /**
     * @param timeoutSeconds    화면 요청 한 번의 전체 시간 한도(재시도 포함) — 점수 묶음·초안·검증·요약·health. 90, 1 이상 (SCRUM-321)
     * @param maxBatch          점수 묶음 크기. 500, 1~500 (SCRUM-321)
     * @param askTimeoutSeconds 질문 답변(`/v1/cases/ask`)만의 전체 시간 한도(재시도 포함, 규약 6.2). 30 (SCRUM-291)
     * @param askEnabled        질문 답변 켜기. 환경 변수 {@code AML_AI_ASK_ENABLED}, 기본 false — 읽는 쪽은 cases (SCRUM-291)
     * @param askPerMinute      질문하기(A8) 한 사람의 최근 60초 한도. 넘으면 429 QUESTION_RATE_LIMITED. 5 — 세는 쪽은 cases (SCRUM-302)
     * @param referenceSearchTimeoutSeconds 참고 사례 검색(`/v1/references/search`)만의 전체 시간 한도(재시도 포함, 규약 6.2). 8 (SCRUM-309)
     * @param referenceSearchEnabled        참고 사례 검색 켜기. 환경 변수 {@code AML_AI_REFERENCE_SEARCH_ENABLED}, 기본 false — 읽는 쪽은 str (SCRUM-309)
     * @param strIndexTimeoutSeconds 승인 STR 색인(`/v1/references/str`)만의 전체 시간 한도(재시도 포함). 30 (SCRUM-357)
     * @param strIndexEnabled        승인 STR 색인 켜기. 환경 변수 {@code AML_AI_STR_INDEX_ENABLED}, 기본 false — 읽는 쪽은 str 승인 뒤 트리거 (SCRUM-357)
     */
    public record Ai(String baseUrl, String internalKey, @DefaultValue("90") int timeoutSeconds,
                     @DefaultValue("500") int maxBatch, boolean mock,
                     @DefaultValue("30") int askTimeoutSeconds, @DefaultValue("false") boolean askEnabled,
                     @DefaultValue("5") int askPerMinute,
                     @DefaultValue("8") int referenceSearchTimeoutSeconds,
                     @DefaultValue("false") boolean referenceSearchEnabled,
                     @DefaultValue("30") int strIndexTimeoutSeconds,
                     @DefaultValue("false") boolean strIndexEnabled) {

        /** 규약 6.2 점수 묶음 상한. */
        private static final int MAX_BATCH_LIMIT = 500;

        /** 0·음수면 첫 시도도 못 보내고 기록 없이 AI_TIMEOUT 만 나서 AI 장애처럼 보인다 → 기동 때 막는다. */
        /** 참고 사례 검색 칸이 없을 때의 기본값 — 예전 8칸 생성자가 쓴다. */
        private static final int DEFAULT_REFERENCE_SEARCH_TIMEOUT_SECONDS = 8;
        /** 승인 STR 색인 칸이 없을 때의 기본값 — 예전 8칸·10칸 생성자가 쓴다. */
        private static final int DEFAULT_STR_INDEX_TIMEOUT_SECONDS = 30;

        @ConstructorBinding
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
            if (askPerMinute <= 0) {   // 0 이면 아무도 못 묻는데 버튼은 보인다 → 기동 때 막는다
                throw new IllegalArgumentException("aml.ai.ask-per-minute 는 1 이상이어야 함: " + askPerMinute);
            }
            if (referenceSearchTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "aml.ai.reference-search-timeout-seconds 는 1 이상이어야 함: " + referenceSearchTimeoutSeconds);
            }
            if (strIndexTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "aml.ai.str-index-timeout-seconds 는 1 이상이어야 함: " + strIndexTimeoutSeconds);
            }
        }

        /**
         * 예전 8칸 생성자 — 참고 사례 검색 칸은 기본값(8초 · 꺼짐). 칸을 더해도 이 모양으로 부르는 테스트가 그대로이게 (SCRUM-309).
         * 설정 바인딩은 위 정식 생성자(`@ConstructorBinding`)만 쓴다.
         */
        public Ai(String baseUrl, String internalKey, int timeoutSeconds, int maxBatch, boolean mock,
                  int askTimeoutSeconds, boolean askEnabled, int askPerMinute) {
            this(baseUrl, internalKey, timeoutSeconds, maxBatch, mock, askTimeoutSeconds, askEnabled, askPerMinute,
                    DEFAULT_REFERENCE_SEARCH_TIMEOUT_SECONDS, false);
        }

        /**
         * 309 모양 10칸 생성자 — 승인 STR 색인 칸은 기본값(30초 · 꺼짐). 칸을 더해도 이 모양으로 부르는 테스트가 그대로이게 (SCRUM-357).
         * 설정 바인딩은 위 정식 생성자(`@ConstructorBinding`)만 쓴다.
         */
        public Ai(String baseUrl, String internalKey, int timeoutSeconds, int maxBatch, boolean mock,
                  int askTimeoutSeconds, boolean askEnabled, int askPerMinute,
                  int referenceSearchTimeoutSeconds, boolean referenceSearchEnabled) {
            this(baseUrl, internalKey, timeoutSeconds, maxBatch, mock, askTimeoutSeconds, askEnabled, askPerMinute,
                    referenceSearchTimeoutSeconds, referenceSearchEnabled, DEFAULT_STR_INDEX_TIMEOUT_SECONDS, false);
        }
    }

    public record DevUsers(boolean enabled, String password) {}

    public record Cors(String allowedOrigin) {}
}
