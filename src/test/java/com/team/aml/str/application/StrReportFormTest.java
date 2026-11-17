package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.application.CaseTransaction;
import com.team.aml.cases.domain.TransactionDirection;
import com.team.aml.cases.domain.TransactionScope;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서식 스냅샷 조립 (스프링 없이). 기준: 노션 「STR 보고서 양식 필드 정의」 필드 표, 서식 회의 10/30 결정 2·3·5,
 * 계약 사본 contracts/examples/draft_response.json 의 facts 모양.
 */
class StrReportFormTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String CASE_ACCOUNT = "10_8000EBD30";

    private static JsonNode facts() throws Exception {
        return OM.readTree("""
                {"reporting_institution": "Portugal Bank #4507",
                 "subject": {"entity_id": "80062E240", "entity_name": "Sole Proprietorship #50438",
                             "entity_type": "SOLE_PROPRIETORSHIP", "account_key": "10_8000EBD30"},
                 "customer": {"industry": "RETAIL", "account_purpose": "SALES_DEPOSIT",
                              "onboarded_at": "2021-03-14", "country_code": "PT"}}
                """);
    }

    private static CaseTransaction tx(long id, String from, String to, TransactionDirection dir, String format,
                                      String paidCur, String paid, String recvCur, String recv) {
        return new CaseTransaction(id, from, to, LocalDateTime.parse("2022-09-02T00:14:00").plusMinutes(id % 100),
                new BigDecimal(paid), paidCur, new BigDecimal(recv), recvCur, format, dir, TransactionScope.EVIDENCE,
                false);
    }

    private static List<NarrativeSection> body() {
        return StrSection.V2.stream().map(s -> new NarrativeSection(s, "본문 " + s, List.of(), List.of())).toList();
    }

    private static StrReportForm.Input input(JsonNode facts, List<CaseTransaction> txs) {
        return new StrReportForm.Input("CASE-2026-000010", "ALT-20220903-000142", 123L, CASE_ACCOUNT,
                Map.of(CASE_ACCOUNT, "First Bank of Dallas", "20_AAA", "Canada Bank #26"), facts,
                "김조사", "박승인", txs, List.of("VI-2-9", "VI-4-26"), 3, 4, body(),
                Instant.parse("2026-11-17T05:00:00.123456Z"), "a".repeat(64), "gen-1", "narrative_v4", "v-1",
                "verify_v1");
    }

    // 1
    @Test
    void 정상_표제부부터_VII와_결재정보가_양식_필드대로_채워진다() throws Exception {
        ObjectNode s = StrReportForm.build(input(facts(), List.of(
                tx(104221, "20_AAA", CASE_ACCOUNT, TransactionDirection.INBOUND, "ACH", "Euro", "11000.00", "US Dollar", "12500.00"))));

        assertThat(s.path("institution").path("name").asText()).isEqualTo("Portugal Bank #4507");
        assertThat(s.path("institution").path("responsible_name").asText()).isEqualTo("박승인");   // I-2 승인자
        assertThat(s.path("institution").path("staff_name").asText()).isEqualTo("김조사");         // I-3 담당
        assertThat(s.path("institution").path("staff_phone").isNull()).isTrue();                   // I-4 지어내지 않음
        assertThat(s.path("subject").path("entity_name").asText()).isEqualTo("Sole Proprietorship #50438");
        assertThat(s.path("subject").path("entity_id").asText()).isEqualTo("80062E240");
        assertThat(s.path("account").path("account_key").asText()).isEqualTo(CASE_ACCOUNT);
        assertThat(s.path("account").path("opened_at").isNull()).isTrue();              // IV-84 원천 미제공 (#123 리뷰)
        assertThat(s.toString()).doesNotContain("2021-03-14");                          // 고객 등록일을 개설일로 쓰지 않음
        assertThat(s.path("account").path("institution").asText()).isEqualTo("First Bank of Dallas");   // IV-85 계좌 은행
        assertThat(s.path("institution").path("name").asText()).isNotEqualTo("First Bank of Dallas");    // I-1 과 다를 수 있다
        assertThat(s.path("account").path("holder_country_code").asText()).isEqualTo("PT");
        assertThat(s.path("suspicion").path("types")).extracting(JsonNode::asText).containsExactly("VI-2-9", "VI-4-26");
        assertThat(s.path("suspicion").path("level_branch").asInt()).isEqualTo(3);
        assertThat(s.path("suspicion").path("level_hq").asInt()).isEqualTo(4);
        assertThat(s.path("narrative").path("sections")).hasSize(StrSection.V2.size());
        assertThat(s.path("narrative").path("related_docs").path("case_no").asText()).isEqualTo("CASE-2026-000010");
        assertThat(s.path("narrative").path("related_docs").path("alert_no").asText()).isEqualTo("ALT-20220903-000142");
        assertThat(s.path("narrative").path("related_docs").path("draft_no").asText()).isEqualTo("DRF-000123");
        assertThat(s.path("approval").path("approved_at").asText()).isEqualTo("2026-11-17T05:00:00Z");   // 초 단위
        assertThat(s.path("approval").path("verification_id").asText()).isEqualTo("v-1");
        assertThat(s.path("approval").path("generator_version").asText()).isEqualTo("narrative_v4");
    }

    // 2
    @Test
    void 정상_문서번호와_보고일자는_스냅샷에_없다() throws Exception {   // report_no 칸·제출 때 reported_at
        ObjectNode s = StrReportForm.build(input(facts(), List.of()));

        assertThat(s.has("header")).isFalse();
        assertThat(s.toString()).doesNotContain("report_no").doesNotContain("reported");
    }

    // 3
    @Test
    void 정상_입금은_받은쪽_금액_출금은_보낸쪽_금액_달러는_US_Dollar일때만() throws Exception {
        ObjectNode s = StrReportForm.build(input(facts(), List.of(
                tx(1, "20_AAA", CASE_ACCOUNT, TransactionDirection.INBOUND, "ACH", "Euro", "11000.00", "US Dollar", "12500.50"),
                tx(2, CASE_ACCOUNT, "10_BBB", TransactionDirection.OUTBOUND, "Wire", "Euro", "9000.123456", "US Dollar", "9800.00"))));

        JsonNode in = s.path("transactions").get(0);
        assertThat(in.path("seq").asInt()).isEqualTo(1);
        assertThat(in.path("ts").asText()).isEqualTo("2022-09-02T00:15:00");   // 초가 0이어도 초까지
        assertThat(in.path("kind").asText()).isEqualTo("입금");
        assertThat(in.path("instrument").asText()).isEqualTo("자동이체");
        assertThat(in.path("currency").asText()).isEqualTo("US Dollar");
        assertThat(in.path("amount").asText()).isEqualTo("12500.50");
        assertThat(in.path("usd_amount").asText()).isEqualTo("12500.50");
        assertThat(in.path("counterparty_account_key").asText()).isEqualTo("20_AAA");

        JsonNode out = s.path("transactions").get(1);
        assertThat(out.path("seq").asInt()).isEqualTo(2);
        assertThat(out.path("kind").asText()).isEqualTo("출금");
        assertThat(out.path("instrument").asText()).isEqualTo("전신송금");
        assertThat(out.path("currency").asText()).isEqualTo("Euro");
        assertThat(out.path("amount").asText()).isEqualTo("9000.123456");   // 원본 정밀도 그대로
        assertThat(out.path("usd_amount").isNull()).isTrue();               // 환산값을 만들지 않는다
        assertThat(out.path("counterparty_account_key").asText()).isEqualTo("10_BBB");
    }

    // 4
    @Test
    void 정상_상대계좌는_한번씩_은행번호가_같으면_자행() throws Exception {
        ObjectNode s = StrReportForm.build(input(facts(), List.of(
                tx(1, "20_AAA", CASE_ACCOUNT, TransactionDirection.INBOUND, "ACH", "US Dollar", "1", "US Dollar", "1"),
                tx(2, "20_AAA", CASE_ACCOUNT, TransactionDirection.INBOUND, "ACH", "US Dollar", "1", "US Dollar", "1"),
                tx(3, CASE_ACCOUNT, "10_BBB", TransactionDirection.OUTBOUND, "ACH", "US Dollar", "1", "US Dollar", "1"))));

        JsonNode cps = s.path("counterparties");
        assertThat(cps).hasSize(2);
        assertThat(cps.get(0).path("account_key").asText()).isEqualTo("20_AAA");
        assertThat(cps.get(0).path("same_bank").asBoolean()).isFalse();   // V-2 타행
        assertThat(cps.get(1).path("account_key").asText()).isEqualTo("10_BBB");
        assertThat(cps.get(1).path("same_bank").asBoolean()).isTrue();    // V-1 자행
        assertThat(cps.get(0).path("bank_name").asText()).isEqualTo("Canada Bank #26");   // V-133 accounts.bank_name
        assertThat(cps.get(1).path("bank_name").isNull()).isTrue();                      // 원천에 은행명 없음 → null
    }

    // 5
    @Test
    void 경계_간접거래는_입출금과_상대계좌를_정하지_않는다() throws Exception {
        ObjectNode s = StrReportForm.build(input(facts(), List.of(
                tx(1, "20_AAA", "30_CCC", TransactionDirection.INDIRECT, "Cash", "US Dollar", "5", "US Dollar", "5"))));

        JsonNode t = s.path("transactions").get(0);
        assertThat(t.path("kind").isNull()).isTrue();
        assertThat(t.path("counterparty_account_key").isNull()).isTrue();
        assertThat(t.path("instrument").asText()).isEqualTo("현금");
        assertThat(s.path("counterparties")).isEmpty();
    }

    // 6
    @Test
    void 경계_facts가_비어도_칸은_null로_남고_지어내지_않는다() {
        ObjectNode s = StrReportForm.build(input(OM.createObjectNode(), List.of()));

        assertThat(s.path("institution").path("name").isNull()).isTrue();
        assertThat(s.path("subject").path("entity_name").isNull()).isTrue();
        assertThat(s.path("subject").path("country_code").isNull()).isTrue();
        assertThat(s.path("account").path("account_key").asText()).isEqualTo(CASE_ACCOUNT);   // 케이스 값은 있음
        assertThat(s.path("account").path("opened_at").isNull()).isTrue();
        assertThat(s.path("transactions")).isEmpty();
    }

    // 7
    @Test
    void 경계_대응표에_없는_결제방식은_원문_그대로_은행번호_형식이_아니면_자행여부_모름() {
        assertThat(StrReportForm.instrument("Unknown Format")).isEqualTo("Unknown Format");
        assertThat(StrReportForm.instrument(null)).isNull();
        assertThat(StrReportForm.sameBank(CASE_ACCOUNT, "NOUNDERSCORE")).isNull();
        assertThat(StrReportForm.sameBank("ACC-P1", "10_X")).isNull();
    }

    // 8
    @Test
    void 경계_요청때_근거거래중_지금_근거범위에_없는_번호는_빠지고_missing으로_센다() {   // #123 리뷰
        List<CaseTransaction> all = List.of(
                tx(1, "20_AAA", CASE_ACCOUNT, TransactionDirection.INBOUND, "ACH", "US Dollar", "1", "US Dollar", "1"),
                tx(2, "20_AAA", CASE_ACCOUNT, TransactionDirection.INBOUND, "ACH", "US Dollar", "1", "US Dollar", "1"),
                tx(3, "20_AAA", CASE_ACCOUNT, TransactionDirection.INBOUND, "ACH", "US Dollar", "1", "US Dollar", "1"));

        StrReportForm.EvidenceSelection sel = StrReportForm.selectEvidence(all, List.of(1L, 3L, 999L, 3L));

        assertThat(sel.rows()).extracting(CaseTransaction::transactionId).containsExactly(1L, 3L);   // 2 는 요청에 없음
        assertThat(sel.expected()).isEqualTo(3);   // 중복 제거
        assertThat(sel.missing()).isEqualTo(1);    // 999 — 지금 근거 범위 밖
    }

    // 9
    @Test
    void 경계_요청_근거번호가_없으면_전부이고_missing_0() {
        List<CaseTransaction> all = List.of(
                tx(1, "20_AAA", CASE_ACCOUNT, TransactionDirection.INBOUND, "ACH", "US Dollar", "1", "US Dollar", "1"));

        StrReportForm.EvidenceSelection sel = StrReportForm.selectEvidence(all, List.of());

        assertThat(sel.rows()).hasSize(1);
        assertThat(sel.missing()).isZero();
    }

    // 10
    @Test
    void 경계_요청_스냅샷의_근거_거래_번호만_읽는다() throws Exception {
        assertThat(StrReportForm.evidenceIds(OM.readTree("{\"evidence_transaction_ids\":[104221,104233]}")))
                .containsExactly(104221L, 104233L);
        assertThat(StrReportForm.evidenceIds(OM.readTree("{\"draft_id\":\"DRF-000001\"}"))).isEmpty();
        assertThat(StrReportForm.evidenceIds(null)).isEmpty();
    }
}
