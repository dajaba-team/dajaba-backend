package com.team.aml.str.domain;

import java.util.List;

/** 검증 결과 저장소. 추가만 — 고치기·지우기 메서드는 없다. 구현은 str/infrastructure (Spring Data). */
public interface VerificationResultRepository {

    VerificationResult save(VerificationResult result);

    /** 이 초안에서 이 본문 해시를 검사한 검증 전부. 그중 최신을 고르는 건 ApprovalGate (FR-APR-03, 11/1 결정). */
    List<VerificationResult> findSameBody(long draftId, String narrativeSha256);
}
