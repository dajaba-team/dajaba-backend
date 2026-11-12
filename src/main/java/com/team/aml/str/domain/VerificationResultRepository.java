package com.team.aml.str.domain;

import java.util.List;
import java.util.Optional;

/** 검증 결과 저장소. 추가만 — 고치기·지우기 메서드는 없다. 구현은 str/infrastructure (Spring Data). */
public interface VerificationResultRepository {

    VerificationResult save(VerificationResult result);

    /** 이 초안에서 이 본문 해시를 검사한 검증 전부. 그중 최신을 고르는 건 ApprovalGate (FR-APR-03, 11/1 결정). */
    List<VerificationResult> findSameBody(long draftId, String narrativeSha256);

    /**
     * 이 초안의 가장 최근 검증 한 건 (produced_at desc, 같으면 id desc — S2·ApprovalGate 와 같은 순서). 없으면 empty.
     * 승인함(P1) 한 줄의 검증 표시용 — 승인 대기 중엔 수정·검증을 못 하니 요청 때 게이트가 인정한 검증과 같다.
     */
    Optional<VerificationResult> findLatest(long draftId);
}
