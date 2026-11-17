package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.application.CaseTransaction;
import com.team.aml.cases.domain.TransactionDirection;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.str.domain.NarrativeSection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 확정 보고서 서식 스냅샷 (str_reports.form_snapshot) — 별지 제1호 표제부~VII + 내부 결재 정보.
 * 기준: 노션 「STR 보고서 양식 필드 정의 (별지 제1호 기준)」, 서식 회의 10/30 결정 2·3·5.
 *
 * <p>규칙
 * <ul>
 *   <li>승인 때 한 번 만들고 바꾸지 않는다. 최신 점수·바뀐 고객 정보로 다시 조립하지 않는다(결정 2).</li>
 *   <li>문서번호(표제부 1)는 str_reports.report_no 칸, 보고일자(표제부 2)는 제출 때 reported_at — 스냅샷에 넣지 않는다.</li>
 *   <li>값이 없으면 null. 지어내지 않는다. 빈칸 이유(원천 미제공·서비스 설정 필요·합성 생성 예정)는 PDF 템플릿이 칸마다 붙인다.</li>
 *   <li>금액은 원본 통화 그대로 문자열(정밀도 유지). 달러 환산은 US Dollar 일 때만 같은 값, 아니면 null(안건 19 — 환율표 없음).</li>
 * </ul>
 * 스프링 없이 만들 수 있게 순수 함수로 두고, 입력은 {@link StrReportFormAssembler} 가 모은다.
 */
final class StrReportForm {

    /** str_reports.snapshot_version. 모양을 바꾸면 올린다(지난 보고서는 그 버전 모양 그대로). */
    static final String VERSION = "form_v1";

    static final String US_DOLLAR = "US Dollar";

    /** III-36 거래수단 대응표 (양식 필드 정의 — 초안, 원본 서식 코드 목록 미확인). 없는 값은 원문 그대로. */
    static final Map<String, String> INSTRUMENT_LABELS = Map.of(
            "Cash", "현금",
            "Cheque", "수표",
            "Wire", "전신송금",
            "ACH", "자동이체",
            "Credit Card", "카드",
            "Bitcoin", "가상자산",
            "Reinvestment", "기타(재투자)");

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private StrReportForm() {}

    /**
     * 조립 재료. 사람 이름은 users.display_name, 계좌는 내부 키(가리지 않음 — 보고서 본문은 담당·승인자만 본다, 결정 4).
     * bankNames = 원천 ops.accounts.bank_name (계좌 키 → 은행명). 케이스 계좌(IV-85)와 상대 계좌(V-133). 없는 키·null 은 null.
     */
    record Input(
            String caseNo,
            String alertNo,
            long draftId,
            String caseAccountKey,
            Map<String, String> bankNames,
            JsonNode facts,
            String investigatorName,
            String approverName,
            List<CaseTransaction> evidenceTransactions,
            List<String> suspicionTypes,
            int suspicionLevelBranch,
            int suspicionLevelHq,
            List<NarrativeSection> narrative,
            Instant approvedAt,
            String narrativeSha256,
            String generationId,
            String generatorVersion,
            String verificationId,
            String verifierVersion) {

        Input {
            Objects.requireNonNull(caseNo, "caseNo");
            Objects.requireNonNull(approvedAt, "approvedAt");
            facts = facts == null ? F.objectNode() : facts;
            bankNames = bankNames == null ? Map.of() : Map.copyOf(bankNames);
            evidenceTransactions = evidenceTransactions == null ? List.of() : List.copyOf(evidenceTransactions);
            suspicionTypes = List.copyOf(suspicionTypes);
            narrative = List.copyOf(narrative);
        }
    }

    static ObjectNode build(Input in) {
        JsonNode facts = in.facts();
        JsonNode subject = facts.path("subject");
        JsonNode customer = facts.path("customer");
        String institution = text(facts, "reporting_institution");

        ObjectNode root = F.objectNode();

        // I. 보고기관
        ObjectNode i = root.putObject("institution");
        i.put("name", institution);                     // I-1 〔확인 필요〕 결정 5-8 한국 금융회사 이름 설정값이 생기면 그 값으로
        i.put("responsible_name", in.approverName());   // I-2 보고책임자 = 승인자
        i.put("staff_name", in.investigatorName());     // I-3 보고담당자 = 담당 조사역
        i.putNull("staff_phone");                       // I-4 서비스 설정 필요

        // II. 거래자
        ObjectNode ii = root.putObject("subject");
        ii.put("entity_name", text(subject, "entity_name"));   // II-5 (합성 명칭)
        ii.put("entity_id", text(subject, "entity_id"));       // 병기용 내부 식별자 — 실명번호 칸에 넣지 않는다
        ii.put("entity_type", text(subject, "entity_type"));   // 개인·법인 구분 (COUNTRY·DIRECT 는 확인 불가)
        ii.put("country_code", text(customer, "country_code"));// II-8
        ii.put("industry", text(customer, "industry"));        // II-16 / II-25

        // III. 거래내역 (근거 거래, 거래 순번으로 반복) + V. 송금·수취인
        ArrayNode iii = root.putArray("transactions");
        Map<String, Boolean> counterparties = new LinkedHashMap<>();
        int seq = 0;
        for (CaseTransaction t : in.evidenceTransactions()) {
            ObjectNode row = iii.addObject();
            row.put("seq", ++seq);
            row.put("transaction_id", t.transactionId());
            row.put("ts", t.ts() == null ? null : t.ts().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));   // III-32 원천 시각 그대로(시간대 없음, 초까지)
            row.put("payment_format", t.paymentFormat());
            row.put("instrument", instrument(t.paymentFormat()));         // III-36
            row.put("direction", t.direction() == null ? null : t.direction().name());
            row.put("kind", kind(t.direction()));                         // III-37 입금/출금
            boolean outbound = t.direction() == TransactionDirection.OUTBOUND;
            String currency = outbound ? t.currencyPaid() : t.currencyReceived();
            BigDecimal amount = outbound ? t.amountPaid() : t.amountReceived();
            row.put("currency", currency);                                // III-39
            row.put("amount", plain(amount));                             // III-41 외국환거래금액
            row.put("usd_amount", US_DOLLAR.equals(currency) ? plain(amount) : null);   // III-42
            String counterparty = counterparty(t);
            row.put("counterparty_account_key", counterparty);
            if (counterparty != null) {
                counterparties.putIfAbsent(counterparty, sameBank(in.caseAccountKey(), counterparty));
            }
        }

        // IV. 관련계좌
        ObjectNode iv = root.putObject("account");
        iv.put("account_key", in.caseAccountKey());              // IV-83
        iv.putNull("opened_at");                                // IV-84 원천 미제공 — customer.onboarded_at 은 고객 등록일이라 쓰지 않는다 (#123 리뷰)
        iv.put("institution", in.bankNames().get(in.caseAccountKey()));   // IV-85 그 계좌의 개설 은행(accounts.bank_name) — 보고기관과 다를 수 있다 (#123 리뷰)
        iv.put("account_purpose", text(customer, "account_purpose"));   // IV-88
        iv.put("holder_name", text(subject, "entity_name"));    // IV-90
        iv.put("holder_country_code", text(customer, "country_code"));  // IV-94

        ArrayNode v = root.putArray("counterparties");
        counterparties.forEach((key, same) -> {
            ObjectNode c = v.addObject();
            c.put("account_key", key);                          // V-109 / V-129
            if (same == null) {
                c.putNull("same_bank");
            } else {
                c.put("same_bank", same);                       // true = V-1 자행, false = V-2 타행
            }
            c.put("bank_name", in.bankNames().get(key));          // V-133 accounts.bank_name, 없으면 null
        });

        // VI. 의심 유형·정도
        ObjectNode vi = root.putObject("suspicion");
        ArrayNode types = vi.putArray("types");
        in.suspicionTypes().forEach(types::add);
        vi.put("level_branch", in.suspicionLevelBranch());
        vi.put("level_hq", in.suspicionLevelHq());

        // VII. 서술부
        ObjectNode vii = root.putObject("narrative");
        ArrayNode sections = vii.putArray("sections");
        for (NarrativeSection n : in.narrative()) {
            sections.addObject().put("section", n.section().name()).put("text", n.text());
        }
        ObjectNode docs = vii.putObject("related_docs");
        docs.put("case_no", in.caseNo());
        docs.put("alert_no", in.alertNo());
        docs.put("draft_no", DisplayNumber.draftNo(in.draftId()));

        // (서식 외) 내부 결재 정보 — 5년 보존 근거
        ObjectNode a = root.putObject("approval");
        a.put("investigator_name", in.investigatorName());
        a.put("approver_name", in.approverName());
        a.put("approved_at", in.approvedAt().truncatedTo(ChronoUnit.SECONDS).toString());   // 시스템 시각은 초 단위 UTC Z
        a.put("narrative_sha256", in.narrativeSha256());
        a.put("generation_id", in.generationId());
        a.put("generator_version", in.generatorVersion());
        a.put("verification_id", in.verificationId());
        a.put("verifier_version", in.verifierVersion());

        return root;
    }

    static String instrument(String paymentFormat) {
        return paymentFormat == null ? null : INSTRUMENT_LABELS.getOrDefault(paymentFormat, paymentFormat);
    }

    static String kind(TransactionDirection direction) {
        if (direction == null) return null;
        return switch (direction) {
            case INBOUND -> "입금";
            case OUTBOUND -> "출금";
            case INDIRECT -> null;   // 케이스 계좌가 양쪽 어디에도 없음 — 거래자 기준 방향을 정할 수 없다
        };
    }

    /** 케이스 계좌 반대편. 간접 거래는 상대를 정할 수 없어 null. */
    static String counterparty(CaseTransaction t) {
        if (t.direction() == TransactionDirection.INBOUND) return t.fromAccountKey();
        if (t.direction() == TransactionDirection.OUTBOUND) return t.toAccountKey();
        return null;
    }

    /** account_key = {bank_id}_{account_no} (규약 2절). 은행 번호가 같으면 자행. 형식이 아니면 null(모름). */
    static Boolean sameBank(String caseAccountKey, String otherAccountKey) {
        String a = bankId(caseAccountKey);
        String b = bankId(otherAccountKey);
        return a == null || b == null ? null : a.equals(b);
    }

    private static String bankId(String accountKey) {
        if (accountKey == null) return null;
        int sep = accountKey.indexOf('_');
        return sep <= 0 ? null : accountKey.substring(0, sep);
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    /**
     * 근거 거래 고르기. wantedIds(요청 스냅샷) 가 비면 전부. 요청 때 번호 중 지금 근거 범위에 없는 것은 missing 으로 센다
     * — 스냅샷은 승인 뒤 못 고치므로 호출 쪽이 경고를 남긴다(#123 리뷰). 순서는 all 그대로.
     */
    static EvidenceSelection selectEvidence(List<CaseTransaction> all, List<Long> wantedIds) {
        if (wantedIds.isEmpty()) {
            return new EvidenceSelection(List.copyOf(all), 0, 0);
        }
        Set<Long> wanted = new LinkedHashSet<>(wantedIds);
        List<CaseTransaction> rows = all.stream().filter(t -> wanted.contains(t.transactionId())).toList();
        Set<Long> found = new HashSet<>();
        rows.forEach(t -> found.add(t.transactionId()));
        return new EvidenceSelection(rows, wanted.size(), (int) wanted.stream().filter(id -> !found.contains(id)).count());
    }

    /** expected = 요청 때 근거 거래 번호 수(중복 제거), missing = 그중 지금 근거 범위에서 못 찾은 수. */
    record EvidenceSelection(List<CaseTransaction> rows, int expected, int missing) {
        EvidenceSelection {
            rows = List.copyOf(rows);
        }
    }

    /** 요청 스냅샷의 evidence_transaction_ids. 없거나 비면 빈 목록 = 거르지 않음. */
    static List<Long> evidenceIds(JsonNode requestSnapshot) {
        List<Long> ids = new ArrayList<>();
        if (requestSnapshot != null) {
            requestSnapshot.path("evidence_transaction_ids").forEach(n -> {
                if (n.canConvertToLong()) ids.add(n.asLong());
            });
        }
        return ids;
    }
}
