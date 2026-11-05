package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 기준: str.md "ApprovalGate가 막는 여섯 경우" 중 검증·역할, AI 09_decisions 10/27(3차 검토).
 * 케이스 상태·본인 승인·반려 뒤 같은 본문은 cases 도메인이 develop에 들어온 뒤 더한다.
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

    /** 이 초안의 "지금 본문"을 검사한 검증 결과. 상태만 바꿔 가며 쓴다. */
    private static VerificationResult verificationOf(StrDraft draft, VerificationStatus status) {
        int revisionNo = draft.getCurrentRevision().getRevisionNo();
        return VerificationResult.of("v-1", draft.getGenerationId(), revisionNo,
                NarrativeHash.of(draft.currentBody()), status);
    }

    // 1
    @Test
    void 지금_본문을_통과한_검증이면_게이트를_통과한다() {
        StrDraft draft = newDraft();
        VerificationResult passed = verificationOf(draft, VerificationStatus.PASSED);

        assertThatCode(() -> ApprovalGate.requireVerified(draft, passed))
                .doesNotThrowAnyException();
    }

    // 2
    @Test
    void 검증한_뒤에_본문을_고치면_DraftNotVerifiedException() {
        StrDraft draft = newDraft();
        VerificationResult passed = verificationOf(draft, VerificationStatus.PASSED);   // 1번(AI 원문)을 검증

        draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR);               // 그 뒤에 고침 → 2번

        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, passed))
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

            assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, result))
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

        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, otherDraftsPass))
                .isInstanceOf(DraftNotVerifiedException.class);
    }

    // 5
    @Test
    void 검증_기록이_없으면_DraftNotVerifiedException() {
        StrDraft draft = newDraft();                       // 만들기만 하고 검증 버튼을 안 누름

        assertThatThrownBy(() -> ApprovalGate.requireVerified(draft, null))
                .isInstanceOf(DraftNotVerifiedException.class)      // NPE(500)가 아니라 409
                .hasMessageContaining("검증 기록 없음");
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

        assertThatCode(() -> ApprovalGate.requireVerified(draft, passedOnOriginal))
                .doesNotThrowAnyException();
    }

    // 7
    @Test
    void 잠기거나_대체된_초안은_검증이_통과여도_DraftLockedException() {
        StrDraft locked = newDraft();
        VerificationResult lockedPass = verificationOf(locked, VerificationStatus.PASSED);
        locked.lock();                                            // 이미 승인됨

        StrDraft superseded = newDraft();
        VerificationResult supersededPass = verificationOf(superseded, VerificationStatus.PASSED);
        superseded.supersede();                                   // 재생성되어 밀려남

        assertThatThrownBy(() -> ApprovalGate.requireVerified(locked, lockedPass))
                .isInstanceOf(DraftLockedException.class);
        assertThatThrownBy(() -> ApprovalGate.requireVerified(superseded, supersededPass))
                .isInstanceOf(DraftLockedException.class);

        // 검증이 없어도 "검증 없음"보다 "초안이 사용 중 아님"이 먼저다
        assertThatThrownBy(() -> ApprovalGate.requireVerified(locked, null))
                .isInstanceOf(DraftLockedException.class);
    }

    // 8
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
