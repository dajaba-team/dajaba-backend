package com.team.aml.str.domain;

import com.team.aml.shared.domain.DomainException;

/** 끝난(만료·실패·성공) 초안 요청에 AI 응답이 늦게 왔을 때. 409 DRAFT_REQUEST_CLOSED — 저장하지 않는다 (미결 21 ⑤). */
public class DraftRequestClosedException extends DomainException {
    public DraftRequestClosedException(StrDraftRequestStatus status) {
        super("이미 끝난 초안 요청입니다. 현재 상태: " + status);
    }
}
