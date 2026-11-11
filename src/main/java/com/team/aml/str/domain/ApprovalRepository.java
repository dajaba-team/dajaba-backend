package com.team.aml.str.domain;

import java.util.Optional;

/** 승인·반려 기록 저장소. 추가만 — 고치기·지우기 메서드는 없다. 구현은 str/infrastructure (Spring Data). */
public interface ApprovalRepository {

    Approval save(Approval approval);

    /** 이 초안의 가장 최근 반려 (created_at desc, id desc). 보완 요청 뒤 재요청 판단·S2 last_rejection 에 쓴다. */
    Optional<Approval> findLatestRejection(long draftId);
}
