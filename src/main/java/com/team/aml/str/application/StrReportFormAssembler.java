package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.application.CaseTransaction;
import com.team.aml.cases.application.CaseTransactions;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.TransactionOrder;
import com.team.aml.cases.domain.TransactionScope;
import com.team.aml.source.domain.Account;
import com.team.aml.source.domain.AccountRepository;
import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.VerificationResult;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 승인(P2) 트랜잭션 안에서 서식 스냅샷 재료를 모은다. 조립 규칙은 {@link StrReportForm}.
 * <ul>
 *   <li>케이스 번호·계좌·담당자 — Case 는 읽기만 (StrCaseChangeReader 와 같은 방식, 02_structure "읽기 참조")</li>
 *   <li>근거 거래 — cases 공개 조회 CaseTransactions(EVIDENCE) (결정 3 "CASES 는 확인된 계좌·거래·담당자 정보를 공개 조회로").
 *       요청 스냅샷의 evidence_transaction_ids 가 있으면 그 거래만</li>
 *   <li>경보 번호 — 초안 요청 때 AI 에 보낸 요청 스냅샷(alert.alert_id). AI 에 보낸 값과 같은 근거로 남긴다</li>
 *   <li>facts — 초안의 AI 응답 facts(승인된 본문이 인용한 숫자와 같은 출처)</li>
 *   <li>은행명 — 원천 ops.accounts.bank_name (source AccountRepository 읽기만). 케이스 계좌(IV-85)·상대 계좌(V-133)</li>
 * </ul>
 * 요청 때 근거 거래 중 지금 근거 범위에 없는 것이 있으면 WARN 한 줄(건수만 — 계좌·금액은 남기지 않는다).
 * 승인은 막지 않는다 — 〔확인 필요〕 막아야 한다는 결정이 나오면 여기서 예외로 바꾼다 (#123 리뷰).
 */
@Component
class StrReportFormAssembler {

    /** 근거 거래를 읽는 한 페이지 크기. 케이스 하나의 근거 거래는 보통 수십 건. */
    static final int TRANSACTION_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(StrReportFormAssembler.class);

    private final CaseRepository cases;
    private final CaseTransactions caseTransactions;
    private final StrDraftRequestRepository requests;
    private final UserRepository users;
    private final AccountRepository accounts;

    StrReportFormAssembler(CaseRepository cases, CaseTransactions caseTransactions,
                           StrDraftRequestRepository requests, UserRepository users, AccountRepository accounts) {
        this.cases = cases;
        this.caseTransactions = caseTransactions;
        this.requests = requests;
        this.users = users;
        this.accounts = accounts;
    }

    ObjectNode assemble(StrDraft draft, Approval approval, VerificationResult verification, long approverId) {
        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        JsonNode requestSnapshot = requests.findById(draft.getId())
                .map(r -> r.getRequestSnapshot())
                .orElse(null);

        StrReportForm.EvidenceSelection evidence = StrReportForm.selectEvidence(evidence(c.getCaseNo()),
                StrReportForm.evidenceIds(requestSnapshot));
        if (evidence.missing() > 0) {
            log.warn("확정 보고서 근거 거래 일부가 지금 근거 범위에 없음: draft={} expected={} found={}",
                    draft.getId(), evidence.expected(), evidence.expected() - evidence.missing());
        }

        return StrReportForm.build(new StrReportForm.Input(
                c.getCaseNo(),
                requestSnapshot == null ? null : textOrNull(requestSnapshot.path("alert").path("alert_id")),
                draft.getId(),
                c.getAccountKey(),
                bankNames(c.getAccountKey(), evidence.rows()),
                draft.getFacts(),
                displayName(c.getAssigneeId()),
                displayName(approverId),
                evidence.rows(),
                draft.getSuspicionTypes(),
                draft.getSuspicionLevelBranch(),
                draft.getSuspicionLevelHq(),
                draft.currentBody(),
                approval.getCreatedAt(),
                approval.getNarrativeSha256(),
                draft.getGenerationId(),
                draft.getGeneratorVersion(),
                verification.getVerificationId(),
                verification.getVerifierVersion()));
    }

    /** 케이스 근거 거래 전부 (CaseTransactions EVIDENCE, 시각 오름차순). */
    private List<CaseTransaction> evidence(String caseNo) {
        List<CaseTransaction> rows = new ArrayList<>();
        int page = 0;
        Page<CaseTransaction> p;
        do {
            p = caseTransactions.list(caseNo, TransactionScope.EVIDENCE, TransactionOrder.TS_ASC, page++,
                    TRANSACTION_PAGE_SIZE);
            p.forEach(rows::add);
        } while (p.hasNext());
        return rows;
    }

    /** 케이스 계좌 + 상대 계좌의 은행명. 원천에 없는 계좌·은행명 없음은 지도에 넣지 않는다(→ 스냅샷 null). */
    private Map<String, String> bankNames(String caseAccountKey, List<CaseTransaction> rows) {
        Map<String, String> names = new HashMap<>();
        Stream.concat(Stream.of(caseAccountKey),
                        rows.stream().map(StrReportForm::counterparty))
                .filter(Objects::nonNull).distinct()
                .forEach(key -> accounts.findById(key).map(Account::getBankName)
                        .ifPresent(name -> names.put(key, name)));
        return names;
    }

    private String displayName(Long userId) {
        return userId == null ? null : users.findById(userId).map(User::getDisplayName).orElse(null);
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}
