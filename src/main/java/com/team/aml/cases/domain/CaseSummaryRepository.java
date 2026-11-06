package com.team.aml.cases.domain;

import java.util.Optional;

/** 추가만 하는 요약 (V006 에서 UPDATE·DELETE 회수). 현재 요약 = 케이스별 최신 1건(created_at, id 내림차순). */
public interface CaseSummaryRepository {
    CaseSummary save(CaseSummary summary);

    Optional<CaseSummary> findFirstByCaseIdOrderByCreatedAtDescIdDesc(Long caseId);
}
