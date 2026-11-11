package com.team.aml.str.domain;

import com.team.aml.shared.domain.DomainException;

/** 409 NARRATIVE_UNCHANGED — 보완 요청 반려 뒤 본문을 안 고치고 다시 승인 요청 (안건 24, str.md 게이트 4번). */
public class NarrativeUnchangedException extends DomainException {

    public NarrativeUnchangedException() {
        super("보완 요청 뒤 고친 내용이 없습니다");
    }
}
