package com.team.aml.str.application;

import java.time.Instant;

/**
 * 승인 요청(S6)·승인(P2)·반려(P3)의 응답 = 케이스 공통 변경 응답 (계약서 5.3 · 5.4 S6 "→ 공통 변경 응답").
 * 케이스 상태는 cases 공개 유스케이스(SubmitCaseForApproval 등)가 바꾸고, 그 뒤 StrCaseChangeReader 가 읽어 채운다.
 *
 * <p>상태·반려 코드가 문자열인 이유: str api 는 cases 클래스(CaseStatus 등)를 볼 수 없다(ArchUnit
 * "api 는 다른 기능에 의존하지 않는다"). S2 GetStrDraft.Result.caseStatus 와 같은 방식.
 */
public record StrCaseChange(String caseNo, String status, Assignee assignee, String rejectionReasonCode,
                            String currentDraftNo, long version, Instant updatedAt) {

    /** 화면에 보이는 담당자 — 내부 users.id 는 내보내지 않는다 (계약서 5.2 assignee 모양). */
    public record Assignee(String loginId, String displayName) {}
}
