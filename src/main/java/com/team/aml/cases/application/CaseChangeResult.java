package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.RejectionReasonCode;

import java.time.Instant;

/**
 * 케이스 작업 공통 변경 응답 (계약서 5.3, CASES 명세 §4 M). 배정·에스컬레이션·종료가 같은 모양을 돌려준다.
 * currentDraftId 는 str 초안의 내부 번호 — 화면용 draft_no(DRF-000123)로 바꾸는 건 컨트롤러 몫(SCRUM-106).
 */
public record CaseChangeResult(String caseNo, CaseStatus status, UserRef assignee,
                               RejectionReasonCode rejectionReasonCode, Long currentDraftId,
                               long version, Instant updatedAt) {
}
