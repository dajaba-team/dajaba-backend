package com.team.aml.str.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.str.domain.ReportPdfContent;

import java.time.Instant;

/** 보고서 PDF 테스트 재료 — StrReportForm(form_v1) 이 만드는 스냅샷 모양을 손으로 적은 것. */
final class ReportPdfFixtures {

    static final ObjectMapper OM = new ObjectMapper();
    static final String REPORT_NO = "STR-2026-000123";
    static final Instant DUE_AT = Instant.parse("2026-11-20T14:59:59Z");          // 서울 11-20 23:59:59
    static final Instant REPORTED_AT = Instant.parse("2026-11-18T01:02:03Z");     // 서울 11-18 10:02:03

    private ReportPdfFixtures() {}

    static ReportPdfContent preview(ObjectNode snapshot) {
        return new ReportPdfContent(REPORT_NO, null, DUE_AT, null, snapshot, "form_v1", true);
    }

    static ReportPdfContent submitted(ObjectNode snapshot) {
        return new ReportPdfContent(REPORT_NO, REPORTED_AT, DUE_AT, false, snapshot, "form_v1", false);
    }

    /** 근거 거래 n건짜리 스냅샷. 값이 없는 칸(전화·개설일 등)은 null 그대로 — 빈칸 이유가 찍히는지 본다. */
    static ObjectNode snapshot(int transactionCount) {
        ObjectNode root = OM.createObjectNode();
        ObjectNode i = root.putObject("institution");
        i.put("name", "Portugal Bank #4507");
        i.put("responsible_name", "승인자 1");
        i.put("staff_name", "조사역 1");
        i.putNull("staff_phone");

        ObjectNode s = root.putObject("subject");
        s.put("entity_name", "한빛무역");
        s.put("entity_id", "80062E240");
        s.put("entity_type", "CORPORATION");
        s.putNull("country_code");
        s.putNull("industry");

        ArrayNode tx = root.putArray("transactions");
        for (int n = 1; n <= transactionCount; n++) {
            ObjectNode t = tx.addObject();
            t.put("seq", n);
            t.put("transaction_id", 104_220 + n);
            t.put("ts", String.format("2022-09-02T%02d:%02d:00", n % 24, n % 60));
            t.put("payment_format", "Wire");
            t.put("instrument", "전신송금");
            t.put("direction", "INBOUND");
            t.put("kind", "입금");
            t.put("currency", "US Dollar");
            t.put("amount", "42750.5");
            t.put("usd_amount", "42750.5");
            t.put("counterparty_account_key", "23842_8017D32A" + n);
        }

        ObjectNode a = root.putObject("account");
        a.put("account_key", "10_8000EBD30");
        a.putNull("opened_at");
        a.put("institution", "First Bank of Dallas");
        a.putNull("account_purpose");
        a.put("holder_name", "한빛무역");
        a.putNull("holder_country_code");

        ArrayNode cp = root.putArray("counterparties");
        cp.addObject().put("account_key", "23842_8017D32A1").put("same_bank", false).put("bank_name", "Canada Bank #26");
        cp.addObject().put("account_key", "10_80001").put("same_bank", true).putNull("bank_name");

        ObjectNode v = root.putObject("suspicion");
        v.putArray("types").add("DEMO_VI4_12").add("DEMO_VI2_09");   // 고른 순서 ≠ 서식 순서
        v.put("level_branch", 3);
        v.put("level_hq", 4);

        ObjectNode vii = root.putObject("narrative");
        ArrayNode sections = vii.putArray("sections");
        sections.addObject().put("section", "SUBJECT").put("text", "거래자 한빛무역은 법인임.");
        sections.addObject().put("section", "GROUNDS").put("text", "서로 다른 계좌 3곳에서 입금됨.\n하루 안에 집중됨.");
        sections.addObject().put("section", "OPINION").put("text", "의심 거래로 판단함 <script>&</script>");
        ObjectNode docs = vii.putObject("related_docs");
        docs.put("case_no", "CASE-2022-000057");
        docs.put("alert_no", "ALT-20220903-000142");
        docs.put("draft_no", "DRF-000312");

        ObjectNode ap = root.putObject("approval");
        ap.put("investigator_name", "조사역 1");
        ap.put("approver_name", "승인자 1");
        ap.put("approved_at", "2026-11-17T05:00:00Z");
        ap.put("narrative_sha256", "a".repeat(64));
        ap.put("generation_id", "gen-1");
        ap.put("generator_version", "narrative_v4");
        ap.put("verification_id", "v-1");
        ap.put("verifier_version", "verify_v1");
        return root;
    }
}
