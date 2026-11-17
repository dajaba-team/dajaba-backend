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
import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.VerificationResult;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 승인(P2) 트랜잭션 안에서 서식 스냅샷 재료를 모은다. 조립 규칙은 {@link StrReportForm}.
 * <ul>
 *   <li>케이스 번호·계좌·담당자 — Case 는 읽기만 (StrCaseChangeReader 와 같은 방식, 02_structure "읽기 참조")</li>
 *   <li>근거 거래 — cases 공개 조회 CaseTransactions(EVIDENCE) (결정 3 "CASES 는 확인된 계좌·거래·담당자 정보를 공개 조회로").
 *       요청 스냅샷의 evidence_transaction_ids 가 있으면 그 거래만</li>
 *   <li>경보 번호 — 초안 요청 때 AI 에 보낸 요청 스냅샷(alert.alert_id). AI 에 보낸 값과 같은 근거로 남긴다</li>
 *   <li>facts — 초안의 AI 응답 facts(승인된 본문이 인용한 숫자와 같은 출처)</li>
 * </ul>
 */
@Component
class StrReportFormAssembler {

    /** 근거 거래를 읽는 한 페이지 크기. 케이스 하나의 근거 거래는 보통 수십 건. */
    static final int TRANSACTION_PAGE_SIZE = 100;

    private final CaseRepository cases;
    private final CaseTransactions caseTransactions;
    private final StrDraftRequestRepository requests;
    private final UserRepository users;

    StrReportFormAssembler(CaseRepository cases, CaseTransactions caseTransactions,
                           StrDraftRequestRepository requests, UserRepository users) {
        this.cases = cases;
        this.caseTransactions = caseTransactions;
        this.requests = requests;
        this.users = users;
    }

    ObjectNode assemble(StrDraft draft, Approval approval, VerificationResult verification, long approverId) {
        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        JsonNode requestSnapshot = requests.findById(draft.getId())
                .map(r -> r.getRequestSnapshot())
                .orElse(null);

        return StrReportForm.build(new StrReportForm.Input(
                c.getCaseNo(),
                requestSnapshot == null ? null : textOrNull(requestSnapshot.path("alert").path("alert_id")),
                draft.getId(),
                c.getAccountKey(),
                draft.getFacts(),
                displayName(c.getAssigneeId()),
                displayName(approverId),
                evidence(c.getCaseNo(), StrReportForm.evidenceIds(requestSnapshot)),
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

    private List<CaseTransaction> evidence(String caseNo, List<Long> onlyIds) {
        Set<Long> wanted = new HashSet<>(onlyIds);
        List<CaseTransaction> rows = new ArrayList<>();
        int page = 0;
        Page<CaseTransaction> p;
        do {
            p = caseTransactions.list(caseNo, TransactionScope.EVIDENCE, TransactionOrder.TS_ASC, page++,
                    TRANSACTION_PAGE_SIZE);
            p.forEach(t -> {
                if (wanted.isEmpty() || wanted.contains(t.transactionId())) rows.add(t);
            });
        } while (p.hasNext());
        return rows;
    }

    private String displayName(Long userId) {
        return userId == null ? null : users.findById(userId).map(User::getDisplayName).orElse(null);
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}
