package com.team.aml.str.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/** 409 VERIFY_IN_PROGRESS — 같은 초안의 검증이 아직 AI 를 기다리는 중. */
public class VerifyInProgressException extends DomainException {

    public VerifyInProgressException(String draftNo) {
        super("이 초안을 이미 검증하고 있습니다: " + draftNo, Map.of("draft_no", draftNo));
    }
}
