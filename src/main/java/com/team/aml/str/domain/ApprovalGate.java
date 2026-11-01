package com.team.aml.str.domain;

import com.team.aml.user.domain.Role;

/**
 * "승인 요청·승인해도 되나"를 한 곳에서 판단한다 (str.md ApprovalGate).
 * 상태를 바꾸지 않는다. 안 되면 예외를 던지고, 되면 조용히 돌아온다.
 * 케이스 상태·담당자·반려 뒤 같은 본문 검사는 cases 도메인이 들어온 뒤 더한다.
 */
public class ApprovalGate {

    private ApprovalGate() {}

    public static void requireVerified(StrDraft draft, VerificationResult verification) {
        draft.requireActive(); // 잠김·대체된 초안은 검증 이전에 거부

        if (verification == null) {                                                 // ← 추가
            throw new DraftNotVerifiedException("검증 기록 없음");
        }
        if (!draft.getGenerationId().equals(verification.getGenerationId())) {      // ← 추가
            throw new DraftNotVerifiedException("다른 초안의 검증");
        }
        if (!NarrativeHash.of(draft.currentBody()).equals(verification.getNarrativeSha256())) {
            throw new DraftNotVerifiedException("검증 뒤에 본문이 바뀜");
        }

        if (!verification.isPassed()) {                                          // ← 추가
            throw new DraftNotVerifiedException("검증 결과 " + verification.getStatus());
        }
    }

    /** 승인·반려하는 사람의 역할이 승인자인가. */
    public static void requireApprover(Role role) {
        if (role != Role.APPROVER) {
            throw new NotApproverException(role);
        }
    }
}
