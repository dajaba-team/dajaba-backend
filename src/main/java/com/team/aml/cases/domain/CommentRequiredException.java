package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

/**
 * 상신·종결·반려 사유가 비었을 때. 409 COMMENT_REQUIRED.
 * 명세 v0.6 은 사유 누락을 DTO @Valid 400 으로 먼저 막는 안이다 — 이 예외는 그 뒤의 도메인 안전장치.
 */
public class CommentRequiredException extends DomainException {
    public CommentRequiredException(CaseHistoryAction action) {
        super(action + " 에는 사유가 필요합니다");
    }
}
