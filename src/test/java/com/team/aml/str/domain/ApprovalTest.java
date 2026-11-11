package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.RejectionReasonCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 승인·반려 기록과 의심 유형·정도 (스프링 없이). 기준: V009 approvals CHECK, 계약서 S6·P2·P3, str.md 반려 두 갈래(안건 24).
 */
class ApprovalTest {

    private static StrDraft newDraft() {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.putObject("facts").put("total_amount", 98_000_000);
        List<NarrativeSection> body = StrSection.V2.stream()
                .map(s -> new NarrativeSection(s, "AI " + s + " 문장", List.of("period"), List.of(1L)))
                .toList();
        return StrDraft.create(100L, 10L, "gen-1", "narrative_v2", Instant.parse("2026-11-03T01:10:00Z"), payload, body);
    }

    private static VerificationResult verification(StrDraft draft, VerificationStatus status) {
        return VerificationResult.record(100L, "v-1", "gen-1", 1, NarrativeHash.of(draft.currentBody()), status,
                "verify_v1", Instant.parse("2026-11-03T02:00:00Z"), new ObjectMapper().createObjectNode());
    }

    // 1
    @Test
    void 정상_승인_기록은_결정_당시_본문_해시를_담고_반려_칸은_비어_있다() {
        StrDraft draft = newDraft();

        Approval approved = Approval.approved(draft, verification(draft, VerificationStatus.PASSED));

        assertThat(approved.getDecision()).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(approved.getDraftId()).isEqualTo(100L);
        assertThat(approved.getNarrativeSha256()).isEqualTo(NarrativeHash.of(draft.currentBody()));
        assertThat(approved.getReasonCode()).isNull();
        assertThat(approved.getComment()).isNull();
        assertThat(approved.isNeedRevision()).isFalse();
    }

    // 2
    @Test
    void 정상_반려는_두_갈래이고_보완_요청만_재요청_비교_대상이다() {
        StrDraft draft = newDraft();

        Approval needRevision = Approval.rejected(draft, RejectionReasonCode.NEED_REVISION, "근거를 보완해 주세요");
        Approval notReportable = Approval.rejected(draft, RejectionReasonCode.NOT_REPORTABLE, "보고 대상 아님");

        assertThat(needRevision.getDecision()).isEqualTo(ApprovalDecision.REJECTED);
        assertThat(needRevision.isNeedRevision()).isTrue();
        assertThat(notReportable.isNeedRevision()).isFalse();
        assertThat(needRevision.getVerificationResultId()).isNull();
    }

    // 3
    @Test
    void 실패_통과_안_한_검증으로_승인_사유_없는_반려_2001자_사유는_막는다() {
        StrDraft draft = newDraft();

        assertThatThrownBy(() -> Approval.approved(draft, verification(draft, VerificationStatus.LLM_FAILED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Approval.rejected(draft, RejectionReasonCode.NEED_REVISION, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Approval.rejected(draft, RejectionReasonCode.NEED_REVISION, "가".repeat(2_001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Approval.rejected(draft, null, "사유"))
                .isInstanceOf(NullPointerException.class);
    }

    // 4
    @Test
    void 정상_승인_요청과_승인_때_의심_유형과_정도를_남긴다() {
        StrDraft draft = newDraft();

        draft.recordBranchSuspicion(List.of("VI-4", "VI-1"), 3);
        draft.recordHqSuspicion(5);

        assertThat(draft.getSuspicionTypes()).containsExactly("VI-4", "VI-1");
        assertThat(draft.getSuspicionLevelBranch()).isEqualTo(3);
        assertThat(draft.getSuspicionLevelHq()).isEqualTo(5);
    }

    // 5
    @Test
    void 실패_유형_없음_중복_17자_정도_0과_6_잠긴_초안은_막는다() {
        StrDraft draft = newDraft();

        assertThatThrownBy(() -> draft.recordBranchSuspicion(List.of(), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.recordBranchSuspicion(List.of("VI-1", "VI-1"), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.recordBranchSuspicion(List.of("V".repeat(17)), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.recordBranchSuspicion(List.of("VI-1"), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.recordHqSuspicion(6)).isInstanceOf(IllegalArgumentException.class);
        assertThat(draft.getSuspicionTypes()).isEmpty();                       // 실패하면 아무것도 안 바뀜

        draft.lock();
        assertThatThrownBy(() -> draft.recordHqSuspicion(3)).isInstanceOf(DraftLockedException.class);
    }

    // 6
    @Test
    void 반려_기록은_결정_당시_본문을_가리킨다_뒤에_고쳐도_해시는_그대로() {
        StrDraft draft = newDraft();
        Approval rejection = Approval.rejected(draft, RejectionReasonCode.NEED_REVISION, "보완");
        String atRejection = NarrativeHash.of(draft.currentBody());

        draft.revise(Map.of(StrSection.GROUNDS, "보완한 사유"), 7L);

        assertThat(rejection.getNarrativeSha256()).isEqualTo(atRejection)
                .isNotEqualTo(NarrativeHash.of(draft.currentBody()));
    }
}
