package com.team.aml.str.domain;

import com.team.aml.user.domain.Role;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * "승인 요청·승인해도 되나"를 한 곳에서 판단한다 (str.md "ApprovalGate가 막는 여섯 경우").
 * 상태를 바꾸지 않는다. 안 되면 예외를 던지고, 되면 근거가 된 검증을 돌려준다.
 *
 * <p>검증 규칙 (게이트 1·2·3번, FR-APR-03): 같은 초안(generation_id)·같은 본문 해시의 검증이 여럿이면 <b>가장 최근 것만</b> 인정한다.
 * 그게 PASSED 가 아니거나, 요청한 verification_id 가 그 최신과 다르면 409 DRAFT_NOT_VERIFIED.
 * 평가자 점수가 매번 같지 않아서 다시 돌려 좋은 결과만 고르는 것을 막는다 [백엔드 #22 리뷰 11/1].
 * 최신 고르기를 application 이 아니라 여기서 한다 — 규칙을 한 곳에 [11/2 설계·코드 리뷰 질문 2 › 4번].
 *
 * <p>케이스 상태(게이트 6번)와 지금·예전 담당 승인(5번, SELF_APPROVAL)은 cases 가 막는다 — 케이스 이력을 str 이 볼 수 없어서.
 */
public class ApprovalGate {

    /** 최신 = produced_at desc, id desc (V008 verification_results_latest_idx, S2 LatestVerificationJdbcRepository 와 같은 순서). */
    private static final Comparator<VerificationResult> LATEST_FIRST = Comparator
            .comparing(VerificationResult::getProducedAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(VerificationResult::getId, Comparator.nullsLast(Comparator.<Long>reverseOrder()));

    private ApprovalGate() {}

    /**
     * 승인 요청 (S6). 순서: 사용 중 초안(409 DRAFT_LOCKED) → 보완 요청 뒤 고쳤나(409 NARRATIVE_UNCHANGED)
     * → 지금 본문 검증(409 DRAFT_NOT_VERIFIED).
     *
     * @param sameBodyVerifications  이 초안에서 지금 본문 해시를 검사한 검증 전부 (VerificationResultRepository.findSameBody)
     * @param requestedVerificationId 화면이 보낸 verification_id — 화면이 본 검증이 지금 최신인지 확인
     * @param lastRejection          이 초안의 가장 최근 반려 (없으면 null). 새 draft_id 는 반려 기록이 없어서 자동으로 제외
     * @return 승인 요청의 근거가 된 검증
     */
    public static VerificationResult requireSubmittable(StrDraft draft, List<VerificationResult> sameBodyVerifications,
                                                        String requestedVerificationId, Approval lastRejection) {
        draft.requireActive();
        requireChangedSinceRejection(draft, lastRejection);
        return requireVerified(draft, sameBodyVerifications, requestedVerificationId);
    }

    /**
     * 지금 본문의 최신 검증이 통과인가 (게이트 1·2·3번). 승인(P2)은 요청 번호 없이 null 로 부른다 — 승인 때도 같은 기준으로 다시 본다.
     *
     * @return 고른 최신 검증 (PASSED)
     */
    public static VerificationResult requireVerified(StrDraft draft, List<VerificationResult> sameBodyVerifications,
                                                     String requestedVerificationId) {
        draft.requireActive(); // 잠김·대체된 초안은 검증 이전에 거부

        if (sameBodyVerifications == null || sameBodyVerifications.isEmpty()) {
            throw new DraftNotVerifiedException("지금 본문을 검사한 검증 없음");
        }
        String hash = NarrativeHash.of(draft.currentBody());
        List<VerificationResult> thisDraft = sameBodyVerifications.stream()
                .filter(v -> draft.getGenerationId().equals(v.getGenerationId()))
                .toList();
        if (thisDraft.isEmpty()) {
            throw new DraftNotVerifiedException("다른 초안의 검증");
        }
        VerificationResult latest = thisDraft.stream().min(LATEST_FIRST).orElseThrow();
        if (!hash.equals(latest.getNarrativeSha256())) {
            throw new DraftNotVerifiedException("검증 뒤에 본문이 바뀜");
        }
        if (requestedVerificationId != null && !requestedVerificationId.equals(latest.getVerificationId())) {
            throw new DraftNotVerifiedException("요청한 검증이 지금 본문의 최신 검증이 아님");
        }
        if (!latest.isPassed()) {
            throw new DraftNotVerifiedException("검증 결과 " + latest.getStatus());
        }
        return latest;
    }

    /**
     * 보완 요청 반려 뒤 본문이 반려 당시와 같으면 409 NARRATIVE_UNCHANGED (게이트 4번, 안건 24).
     * 보고 안 함 반려는 다시 요청할 수 없어서(케이스가 막음) 보지 않는다. 다른 초안의 반려도 보지 않는다(cases.md "새 draft_id는 제외").
     */
    public static void requireChangedSinceRejection(StrDraft draft, Approval lastRejection) {
        if (lastRejection == null || !lastRejection.isNeedRevision()
                || !Objects.equals(lastRejection.getDraftId(), draft.getId())) {
            return;
        }
        if (NarrativeHash.of(draft.currentBody()).equals(lastRejection.getNarrativeSha256())) {
            throw new NarrativeUnchangedException();
        }
    }

    /** 승인·반려하는 사람의 역할이 승인자인가. */
    public static void requireApprover(Role role) {
        if (role != Role.APPROVER) {
            throw new NotApproverException(role);
        }
    }
}
