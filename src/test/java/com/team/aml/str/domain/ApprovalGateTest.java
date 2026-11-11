package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.domain.ForbiddenException;
import com.team.aml.user.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * 승인 게이트 단위 테스트 (스프링 없이 돈다).
 * 기준: str.md "ApprovalGate가 막는 여섯 경우" 1~5번, AI 09_decisions 10/27(3차 검토),
 * 같은 본문 검증 중 최신만 인정(백엔드 #22 리뷰 11/1, FR-APR-03), 11/2 설계·코드 리뷰 질문 2 › 4번(게이트가 직접 고름·NARRATIVE_UNCHANGED).
 * 케이스 상태(6번)·지금·예전 담당 승인(SELF_APPROVAL)은 cases 가 막는다.
 */
public class ApprovalGateTest {

    private static final String GENERATION_ID = "gen-1";
    private static final Long INVESTIGATOR = 7L;

    private static JsonNode facts() {
        return new ObjectMapper().createObjectNode().put("total_amount", 98_000_000);
    }

    /** AI 원문. StrDraftTest와 같은 모양: 단락 전부에 문장과 근거 번호. */
    private static List<NarrativeSection> aiNarrative() {
        return StrSection.V1.stream()
                .map(s -> new NarrativeSection(s, "AI " + s + " 문장", List.of(5_120_000L + s.ordinal())))
                .toList();
    }

    private static final Long DRAFT_ID = 100L;
    private static final String GENERATOR_VERSION = "test-v1";
    private static final Instant PRODUCED_AT = Instant.parse("2026-11-03T01:10:00Z");

    /** AI 응답 전체. facts 는 payload 안에 들어 있다 (StrDraft.create 규칙). */
    private static JsonNode payload() {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.set("facts", facts());
        return payload;
    }

    private static StrDraft draft(Long caseId, String generationId, List<NarrativeSection> narrative) {
        return StrDraft.create(DRAFT_ID, caseId, generationId, GENERATOR_VERSION, PRODUCED_AT, payload(), narrative);
    }

    private static StrDraft newDraft() {
        return draft(10L, GENERATION_ID, aiNarrative());
    }

    /** 이 초안의 "지금 본문"을 검사한 검증 결과. 번호·시각·상태만 바꿔 가며 쓴다. */
    private static VerificationResult verificationOf(StrDraft draft, String verificationId, String producedAt,
                                                     VerificationStatus status) {
        int revisionNo = draft.getCurrentRevision().getRevisionNo();
        return VerificationResult.record(DRAFT_ID, verificationId, draft.getGenerationId(), revisionNo,
                NarrativeHash.of(draft.currentBody()), status, "verify_v1", Instant.parse(producedAt),
                new ObjectMapper().createObjectNode());
    }

    private static VerificationResult verificationOf(StrDraft draft, VerificationStatus status) {
        return verificationOf(draft, "v-1", "2026-11-03T02:00:00Z", status);
    }

    /** 이 초안의 지금 본문을 반려한 기록. */
    private static Approval rejectionOf(StrDraft draft, RejectionReasonCode code) {
        return Approval.rejected(draft, code, "근거 거래 설명을 보완해 주세요");
    }

    // ── 검증 (게이트 1·2·3번) ─────────────────────────────────

    // 1
    @Test
    void 지금_본문을_통과한_검증이면_게이트를_통과하고_그_검증을_돌려준다() {
        StrDraft draft = newDraft();
        VerificationResult passed = verificationOf(draft, VerificationStatus.PASSED);

        assertThat(ApprovalGate.requireVerified(draft, List.of(passed), "v-1")).isSameAs(passed);
    }

    // 2
    @Test
    void 검증한_뒤에_본문을_고치면_DraftNotVerifiedException() {
        StrDraft draft = newDraft();
        VerificationResult passed = verificationOf(draft, VerificationStatus.PASSED);   // 1번(AI 원문)을 검증

        draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR);               // 그 뒤에 고침 → 2번

        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, List.of(passed), "v-1"))
                .isInstanceOf(DraftNotVerifiedException.class)
                .message()
                .doesNotContain("고친 HOW");                                         // 메시지에 본문 글자 없음
    }

    // 3
    @Test
    void 검증_결과가_PASSED가_아니면_DraftNotVerifiedException() {
        StrDraft draft = newDraft();

        for (VerificationStatus notPassed : List.of(
                VerificationStatus.CODE_FAILED,
                VerificationStatus.LLM_FAILED,
                VerificationStatus.VERIFY_UNAVAILABLE)) {          // "검증 못 함"도 승인 불가

            VerificationResult result = verificationOf(draft, notPassed);   // 본문 해시는 지금 본문과 같다

            assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, List.of(result), "v-1"))
                    .isInstanceOf(DraftNotVerifiedException.class)
                    .hasMessageContaining(notPassed.name());       // 어떤 결과였는지 메시지에 남는다
        }
    }

    // 4
    @Test
    void 본문이_같아도_다른_초안의_검증이면_DraftNotVerifiedException() {
        StrDraft draft = newDraft();                                                         // gen-1
        StrDraft regenerated = draft(10L, "gen-2", aiNarrative());       // 같은 문장으로 재생성
        VerificationResult otherDraftsPass = verificationOf(regenerated, VerificationStatus.PASSED);

        // 전제: 본문이 같아서 해시도 같다 — 해시만 보면 통과해 버리는 경우
        assertThat(otherDraftsPass.getNarrativeSha256()).isEqualTo(NarrativeHash.of(draft.currentBody()));

        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, List.of(otherDraftsPass), "v-1"))
                .isInstanceOf(DraftNotVerifiedException.class)
                .hasMessageContaining("다른 초안");
    }

    // 5
    @Test
    void 검증_기록이_없으면_DraftNotVerifiedException() {
        StrDraft draft = newDraft();                       // 만들기만 하고 검증 버튼을 안 누름

        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, List.of(), "v-1"))
                .isInstanceOf(DraftNotVerifiedException.class)      // NPE(500)가 아니라 409
                .hasMessageContaining("검증 없음");
        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, null, null))
                .isInstanceOf(DraftNotVerifiedException.class);
    }

    // 6
    @Test
    void 수정본_번호가_달라도_검증한_본문과_같으면_통과한다() {
        StrDraft draft = newDraft();
        VerificationResult passedOnOriginal = verificationOf(draft, VerificationStatus.PASSED); // 1번(AI 원문)을 검증
        String originalHow = "AI " + StrSection.HOW + " 문장";

        draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR);     // 2번: 고침
        draft.revise(Map.of(StrSection.HOW, originalHow), INVESTIGATOR);    // 3번: 원래 문장으로 되돌림

        // 전제: 번호는 다르고, 본문은 검증한 것과 같다
        assertThat(draft.getCurrentRevision().getRevisionNo()).isEqualTo(3);
        assertThat(passedOnOriginal.getRevisionNo()).isEqualTo(1);

        assertThatCode(() -> ApprovalGate.requireVerified(draft, List.of(passedOnOriginal), "v-1"))
                .doesNotThrowAnyException();
    }

    // 7
    @Test
    void 같은_본문_검증이_여럿이면_최신만_본다_예전_PASSED로는_통과_못_한다() {
        StrDraft draft = newDraft();
        VerificationResult olderPass = verificationOf(draft, "v-old", "2026-11-03T02:00:00Z", VerificationStatus.PASSED);
        VerificationResult newerFail = verificationOf(draft, "v-new", "2026-11-03T03:00:00Z", VerificationStatus.LLM_FAILED);

        // 목록 순서와 상관없이 produced_at 최신이 기준 — 다시 돌려 좋은 결과만 고르는 것 막기
        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, List.of(newerFail, olderPass), "v-old"))
                .isInstanceOf(DraftNotVerifiedException.class);
        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, List.of(olderPass, newerFail), null))
                .isInstanceOf(DraftNotVerifiedException.class)
                .hasMessageContaining("LLM_FAILED");
    }

    // 8
    @Test
    void 최신이_PASSED면_통과하고_요청한_번호가_최신과_다르면_DraftNotVerifiedException() {
        StrDraft draft = newDraft();
        VerificationResult olderFail = verificationOf(draft, "v-old", "2026-11-03T02:00:00Z", VerificationStatus.CODE_FAILED);
        VerificationResult newerPass = verificationOf(draft, "v-new", "2026-11-03T03:00:00Z", VerificationStatus.PASSED);
        List<VerificationResult> both = List.of(olderFail, newerPass);

        assertThat(ApprovalGate.requireVerified(draft, both, "v-new")).isSameAs(newerPass);
        assertThat(ApprovalGate.requireVerified(draft, both, null)).isSameAs(newerPass);   // 승인(P2)은 번호 없이
        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, both, "v-old"))   // 화면이 예전 결과를 보고 누름
                .isInstanceOf(DraftNotVerifiedException.class)
                .hasMessageContaining("최신");
    }

    // ── 보완 요청 뒤 재요청 (게이트 4번) ──────────────────────

    // 9
    @Test
    void 보완_요청_반려_뒤_본문을_안_고치면_NarrativeUnchangedException() {
        StrDraft draft = newDraft();
        Approval rejection = rejectionOf(draft, RejectionReasonCode.NEED_REVISION);   // 1번 본문을 반려
        VerificationResult passed = verificationOf(draft, VerificationStatus.PASSED);  // 반려 전 통과 검증이 남아 있어도

        assertThatThrownBy(() -> ApprovalGate.requireSubmittable(draft, List.of(passed), "v-1", rejection))
                .isInstanceOf(NarrativeUnchangedException.class);
    }

    // 10
    @Test
    void 보완_요청_반려_뒤_고치고_다시_검증했으면_통과한다() {
        StrDraft draft = newDraft();
        Approval rejection = rejectionOf(draft, RejectionReasonCode.NEED_REVISION);
        draft.revise(Map.of(StrSection.WHY_SUSPICIOUS, "보완한 사유"), INVESTIGATOR);
        VerificationResult passedAfterFix = verificationOf(draft, "v-2", "2026-11-03T04:00:00Z", VerificationStatus.PASSED);

        assertThat(ApprovalGate.requireSubmittable(draft, List.of(passedAfterFix), "v-2", rejection))
                .isSameAs(passedAfterFix);
    }

    // 11
    @Test
    void 반려가_없거나_다른_초안의_반려면_본문_비교를_하지_않는다() {
        StrDraft draft = newDraft();
        VerificationResult passed = verificationOf(draft, VerificationStatus.PASSED);
        StrDraft regenerated = StrDraft.create(DRAFT_ID + 1, 10L, "gen-2", GENERATOR_VERSION, PRODUCED_AT, payload(),
                aiNarrative());                                                    // 새 draft_id, 같은 문장
        Approval otherDraftsRejection = rejectionOf(regenerated, RejectionReasonCode.NEED_REVISION);

        assertThat(ApprovalGate.requireSubmittable(draft, List.of(passed), "v-1", null)).isSameAs(passed);
        assertThat(ApprovalGate.requireSubmittable(draft, List.of(passed), "v-1", otherDraftsRejection)).isSameAs(passed);
    }

    // ── 초안 상태·역할 (게이트 5번 일부) ──────────────────────

    // 12
    @Test
    void 잠기거나_대체된_초안은_검증이_통과여도_DraftLockedException() {
        StrDraft locked = newDraft();
        VerificationResult lockedPass = verificationOf(locked, VerificationStatus.PASSED);
        locked.lock();                                            // 이미 승인됨

        StrDraft superseded = newDraft();
        VerificationResult supersededPass = verificationOf(superseded, VerificationStatus.PASSED);
        superseded.supersede();                                   // 재생성되어 밀려남

        assertThatThrownBy(() -> ApprovalGate.requireVerified(locked, List.of(lockedPass), "v-1"))
                .isInstanceOf(DraftLockedException.class);
        assertThatThrownBy(() -> ApprovalGate.requireSubmittable(superseded, List.of(supersededPass), "v-1", null))
                .isInstanceOf(DraftLockedException.class);

        // 검증이 없어도 "검증 없음"보다 "초안이 사용 중 아님"이 먼저다
        assertThatThrownBy(() -> ApprovalGate.requireVerified(locked, List.of(), null))
                .isInstanceOf(DraftLockedException.class);
    }

    // 13
    @Test
    void 승인자만_승인할_수_있다() {
        assertThatCode(() -> ApprovalGate.requireApprover(Role.APPROVER))
                .doesNotThrowAnyException();

        for (Role notApprover : new Role[]{Role.INVESTIGATOR, Role.ADMIN, Role.SYSTEM, null}) {
            assertThatThrownBy(() -> ApprovalGate.requireApprover(notApprover))
                    .isInstanceOf(NotApproverException.class)
                    .isInstanceOf(ForbiddenException.class);
        }
    }
}
