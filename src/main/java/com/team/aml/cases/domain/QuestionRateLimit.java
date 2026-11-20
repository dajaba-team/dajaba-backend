package com.team.aml.cases.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 질문하기 횟수 제한 판정 (설계 문서 7.4, SCRUM-302) — 순수 함수. 시계·DB 는 부르는 쪽(AskCaseQuestion)이 준다.
 * 창은 60초 고정, 한도는 설정(aml.ai.ask-per-minute).
 */
public final class QuestionRateLimit {

    /** 세는 창. "분당 N회"의 1분. */
    public static final Duration WINDOW = Duration.ofSeconds(60);

    private QuestionRateLimit() {}

    /**
     * @param recentCount  창 안(now - 60초 이후) 이 사람의 질문 수
     * @param oldestInWindow 창 안 가장 오래된 질문 시각 (recentCount 가 0 이면 null 가능)
     * @throws QuestionRateLimitedException recentCount ≥ limit 이면. retry_after_seconds 는 그 질문이 창을 벗어날 때까지(올림, 최소 1)
     */
    public static void check(String caseNo, int limit, long recentCount, Instant oldestInWindow, Instant now) {
        if (recentCount < limit) {
            return;
        }
        long remainingMs = oldestInWindow == null ? WINDOW.toMillis()
                : Duration.between(now, oldestInWindow.plus(WINDOW)).toMillis();
        long seconds = Math.max(1, (remainingMs + 999) / 1000);
        throw new QuestionRateLimitedException(caseNo, Math.min(seconds, WINDOW.toSeconds()));
    }
}
