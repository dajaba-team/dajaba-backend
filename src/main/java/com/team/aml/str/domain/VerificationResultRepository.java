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
     * 승인함(P1) 한 줄의 검증 표시용. 보통은 요청 때 게이트가 인정한 검증과 같다 — 승인 대기 중엔 수정·새 검증을 못 하니까.
     * 예외 하나: DRAFTED 에서 시작한 검증(최대 90초)이 승인 요청 뒤에 저장되면 그쪽이 최신이 된다(VerifyStrDraft 저장 단계는
     * 케이스 상태를 다시 보지 않음, #103 리뷰 3번). 그때 목록은 그 늦은 결과를 보여 주고, 승인(P2) 게이트도 같은 해시의 최신으로
     * 판단하므로 목록과 승인 판단은 어긋나지 않는다. 요청 때 검증을 정확히 남기려면 S6 이 verification_id 를 저장해야 한다(후속).
     */
    Optional<VerificationResult> findLatest(long draftId);
}
