package com.team.aml.shared.domain;

import java.util.Map;

/**
 * 429 의 부모. 기능 규칙으로 정한 횟수 제한(예: 질문하기 분당 한도)을 넘었을 때 쓴다 (SCRUM-302).
 * 하위 클래스 이름이 코드가 된다 — QuestionRateLimitedException → QUESTION_RATE_LIMITED.
 * 인프라 수준 요청 제한(게이트웨이·IP)은 범위 밖이다. details 에 다시 시도할 때까지의 초를 넣는 건 던지는 쪽 몫.
 */
public abstract class TooManyRequestsException extends DomainException {

    protected TooManyRequestsException(String message, Map<String, Object> details) {
        super(message, details);
    }
}
