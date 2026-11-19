package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF 재료 (스프링 없이). 미리보기(S7)는 보고일자 없이 "제출 전", 확정본(S8)은 모의 제출 뒤에만 — 서식 회의 10/30 결정 2.
 */
class ReportPdfContentTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Instant APPROVED_AT = Instant.parse("2026-11-17T05:00:00Z");
    private static final Instant REPORTED_AT = Instant.parse("2026-11-18T01:00:00Z");

    // 1
    @Test
    void 정상_미리보기는_승인된_보고서의_스냅샷과_문서번호로_보고일자_없이() {
        StrReport r = approvedReport();

        ReportPdfContent c = ReportPdfContent.preview(r);

        assertThat(c.preview()).isTrue();
        assertThat(c.reportNo()).isEqualTo(r.getReportNo());
        assertThat(c.reportedAt()).isNull();
        assertThat(c.late()).isNull();
        assertThat(c.reportDueAt()).isEqualTo(r.getReportDueAt());
        assertThat(c.formSnapshot()).isEqualTo(r.getFormSnapshot());
        assertThat(c.snapshotVersion()).isEqualTo("form_v1");
    }

    // 2
    @Test
    void 정상_확정본은_제출_시각과_늦음을_넣는다() {
        StrReport r = approvedReport();
        r.markReported(3L, REPORTED_AT);

        ReportPdfContent c = ReportPdfContent.submitted(r);

        assertThat(c.preview()).isFalse();
        assertThat(c.reportedAt()).isEqualTo(REPORTED_AT);
        assertThat(c.late()).isFalse();
    }

    // 3
    @Test
    void 예외_제출_전_보고서로_확정본을_만들면_호출쪽_버그() {
        StrReport r = approvedReport();

        assertThatThrownBy(() -> ReportPdfContent.submitted(r)).isInstanceOf(IllegalStateException.class);
    }

    // 4
    @Test
    void 예외_미리보기에_보고일자를_넣거나_확정본에_보고일자가_없으면_거부() {
        ObjectNode s = OM.createObjectNode();

        assertThatThrownBy(() -> new ReportPdfContent("STR-2026-000001", REPORTED_AT, APPROVED_AT, false, s, "form_v1", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportPdfContent("STR-2026-000001", null, APPROVED_AT, null, s, "form_v1", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportPdfContent("STR-2026-000001", null, APPROVED_AT, null,
                OM.createArrayNode(), "form_v1", true)).isInstanceOf(IllegalArgumentException.class);
    }

    // 5
    @Test
    void 정상_스냅샷은_복사본이라_밖에서_바꿔도_그대로() {
        ObjectNode s = OM.createObjectNode().put("k", "원래");
        ReportPdfContent c = new ReportPdfContent("STR-2026-000001", null, APPROVED_AT, null, s, "form_v1", true);

        s.put("k", "바뀜");
        ((ObjectNode) c.formSnapshot()).put("k", "또 바뀜");

        assertThat(c.formSnapshot().path("k").asText()).isEqualTo("원래");
    }

    // ---- 도우미 (StrReportTest 와 같은 모양) ----

    private static StrReport approvedReport() {
        ObjectNode payload = OM.createObjectNode();
        payload.putObject("facts").put("total_amount", 98_000_000);
        List<NarrativeSection> body = StrSection.V2.stream()
                .map(s -> new NarrativeSection(s, "AI " + s + " 문장", List.of("period"), List.of(1L)))
                .toList();
        StrDraft draft = StrDraft.create(100L, 10L, "gen-1", "narrative_v2", Instant.parse("2026-11-03T01:10:00Z"),
                payload, body);
        draft.recordBranchSuspicion(List.of("VI-2-9"), 3);
        draft.recordHqSuspicion(4);
        VerificationResult v = VerificationResult.record(100L, "v-1", "gen-1", 1, NarrativeHash.of(draft.currentBody()),
                VerificationStatus.PASSED, "verify_v1", Instant.parse("2026-11-03T02:00:00Z"), OM.createObjectNode());
        ReflectionTestUtils.setField(v, "id", 7L);
        Approval a = Approval.approved(draft, v);
        ReflectionTestUtils.setField(a, "id", 55L);
        draft.lock();
        ObjectNode snapshot = OM.createObjectNode();
        snapshot.putObject("institution").put("name", "모의은행");
        return StrReport.approve(draft, a, 1L, APPROVED_AT, snapshot, "form_v1");
    }
}
