package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseQuestion;
import com.team.aml.cases.domain.CaseQuestionStatus;
import com.team.aml.cases.domain.QuestionOutOfScopeReason;
import com.team.aml.shared.domain.DisplayNumber;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 질문 한 줄 — A8 응답 = C9 목록 한 줄 (계약서 5.3 "A8·C9 질문 한 줄", SCRUM-302). 내부 id 는 questionNo(QST-…)로만 나간다.
 * AI 의 cites·code_checks·kind 는 화면에 주지 않는다. answer 의 계좌는 AI 표기 그대로(설계 문서 13절 8번).
 */
public record CaseQuestionResult(String questionNo, String caseNo, String question, CaseQuestionStatus status,
                                 QuestionOutOfScopeReason outOfScopeReason, String answer,
                                 List<Long> evidenceTransactionIds, UserRef askedBy, Instant createdAt) {

    public CaseQuestionResult {
        evidenceTransactionIds = evidenceTransactionIds == null ? List.of() : List.copyOf(evidenceTransactionIds);
    }

    static CaseQuestionResult of(CaseQuestion q, String caseNo, UserRef askedBy) {
        return new CaseQuestionResult(DisplayNumber.questionNo(q.getId()), caseNo, q.getQuestion(), q.getStatus(),
                q.getOutOfScopeReason(), q.getAnswer(), q.getEvidenceTransactionIds(), askedBy,
                q.getCreatedAt().truncatedTo(ChronoUnit.SECONDS));
    }
}
