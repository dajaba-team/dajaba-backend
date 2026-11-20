package com.team.aml.cases.domain;

import com.team.aml.shared.domain.TooManyRequestsException;

import java.util.Map;

/**
 * 429 QUESTION_RATE_LIMITED — 한 사람이 최근 60초에 한도(aml.ai.ask-per-minute)만큼 이미 물었다 (계약서 3절, SCRUM-302).
 * details.retry_after_seconds = 다시 물을 수 있을 때까지 남은 초(최소 1).
 */
public class QuestionRateLimitedException extends TooManyRequestsException {

    public QuestionRateLimitedException(String caseNo, long retryAfterSeconds) {
        super("잠시 뒤 다시 물어봐 주세요: " + caseNo,
                Map.of("case_no", caseNo, "retry_after_seconds", retryAfterSeconds));
    }
}
