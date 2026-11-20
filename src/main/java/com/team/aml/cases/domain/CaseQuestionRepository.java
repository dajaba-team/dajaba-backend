package com.team.aml.cases.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;

/** 추가만 하는 질문 답변 기록 (V017 에서 UPDATE·DELETE 회수, SCRUM-302). */
public interface CaseQuestionRepository {
    CaseQuestion save(CaseQuestion question);

    /** C9 지난 질문 — 케이스별 최신 먼저(created_at, id 내림차순). */
    Page<CaseQuestion> findByCaseIdOrderByCreatedAtDescIdDesc(Long caseId, Pageable pageable);

    /** 횟수 제한 — 이 사람이 since 이후에 물은 수. */
    long countByCreatedByAndCreatedAtAfter(Long createdBy, Instant since);

    /** 횟수 제한 — since 이후 이 사람의 가장 오래된 질문(retry_after_seconds 계산용). */
    Optional<CaseQuestion> findFirstByCreatedByAndCreatedAtAfterOrderByCreatedAtAsc(Long createdBy, Instant since);
}
