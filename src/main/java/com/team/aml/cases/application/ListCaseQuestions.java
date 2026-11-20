package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseQuestion;
import com.team.aml.cases.domain.CaseQuestionRepository;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * C9 지난 질문 (계약서 5.2, 설계 문서 7.1, SCRUM-302). 담당 조사역·승인자만(A8 과 같은 권한), 최신 먼저.
 * 한 줄 모양은 A8 응답과 같다. 열람 기록은 남기지 않는다(케이스 열람은 상세가 남긴다 — 메모 이력 C8 과 같음).
 */
@Service
public class ListCaseQuestions {

    private final CaseRepository cases;
    private final CaseQuestionRepository questions;
    private final CurrentUser currentUser;
    private final UserRefs userRefs;

    public ListCaseQuestions(CaseRepository cases, CaseQuestionRepository questions, CurrentUser currentUser,
                             UserRefs userRefs) {
        this.cases = cases;
        this.questions = questions;
        this.currentUser = currentUser;
        this.userRefs = userRefs;
    }

    @Transactional(readOnly = true)
    public Page<CaseQuestionResult> execute(String caseNo, int page, int size) {
        Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
        CaseQuestionAccess.require(c, currentUser.require());
        Page<CaseQuestion> rows = questions.findByCaseIdOrderByCreatedAtDescIdDesc(c.getId(), PageRequest.of(page, size));
        Map<Long, UserRef> refs = userRefs.of(rows.getContent().stream().map(CaseQuestion::getCreatedBy).toList());
        return rows.map(q -> CaseQuestionResult.of(q, caseNo, refs.get(q.getCreatedBy())));
    }
}
