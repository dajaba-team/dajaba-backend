package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.EvidenceNotFoundException;
import com.team.aml.cases.domain.TransactionOrder;
import com.team.aml.cases.domain.TransactionScope;
import com.team.aml.detection.application.ListAlertTransactions;
import com.team.aml.detection.domain.AlertTransaction;
import com.team.aml.detection.domain.AlertTransactionOrder;
import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.audit.TargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 케이스 근거 거래 조회 (CASES 명세 API-08·09·10, 계약서 C3·C4·C5). 조회 3역할. 거래는 detection ListAlertTransactions 로만 읽는다.
 * 방향은 케이스 계좌 기준(TransactionDirection). 계좌 가리기는 컨트롤러(AccountDisplay).
 * CONTEXT(경보 밖 참고 거래)는 저장소가 아직 없어 빈 결과 — 화면은 "참고 거래 없음"으로 보인다.
 */
@Service
public class CaseTransactions {

    /** 그래프 한 줄 (C5). 선은 거래 하나, 금액은 받은 쪽 기준(amount_basis = RECEIVED). 계좌는 내부 키. */
    public record Edge(long transactionId, String sourceAccountKey, String targetAccountKey, BigDecimal amount,
                       String currency, TransactionScope scope, LocalDateTime ts) {
    }

    /** nodes 는 선에 나온 계좌, 처음 나온 순서. truncated 면 total 이 returned 보다 크다. */
    public record Graph(List<String> nodeAccountKeys, List<Edge> edges, long totalTransactionCount) {
        public Graph {
            nodeAccountKeys = List.copyOf(nodeAccountKeys);
            edges = List.copyOf(edges);
        }

        public boolean truncated() {
            return totalTransactionCount > edges.size();
        }
    }

    public record Evidence(String caseNo, CaseTransaction transaction, boolean eligibleForStr) {
    }

    private final CaseRepository cases;
    private final ListAlertTransactions alertTransactions;
    private final AccessLogWriter accessLog;

    public CaseTransactions(CaseRepository cases, ListAlertTransactions alertTransactions, AccessLogWriter accessLog) {
        this.cases = cases;
        this.alertTransactions = alertTransactions;
        this.accessLog = accessLog;
    }

    /** C3. 없는 케이스 404. */
    @Transactional(readOnly = true)
    public Page<CaseTransaction> list(String caseNo, TransactionScope scope, TransactionOrder order, int page,
                                      int size) {
        Case c = load(caseNo);
        PageRequest pageable = PageRequest.of(page, size);
        if (scope == TransactionScope.CONTEXT) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        ListAlertTransactions.Page rows = alertTransactions.page(c.getAlertId(), AlertTransactionOrder.valueOf(order.name()),
                pageable.getOffset(), size);
        return new PageImpl<>(rows.items().stream()
                .map(t -> CaseTransaction.of(t, c.getAccountKey(), TransactionScope.EVIDENCE)).toList(),
                pageable, rows.total());
    }

    /**
     * C4. 이 케이스의 근거 거래가 아니면 404 EVIDENCE_NOT_FOUND. 열람 기록을 남긴다(명세 API-09) —
     * access_logs 에 거래 대상이 없어(V004_3 CHECK) 케이스 열람으로 남긴다. 그래서 readOnly 가 아니다.
     */
    @Transactional
    public Evidence evidence(String caseNo, long transactionId) {
        Case c = load(caseNo);
        AlertTransaction t = alertTransactions.one(c.getAlertId(), transactionId)
                .orElseThrow(() -> new EvidenceNotFoundException(caseNo, transactionId));
        accessLog.view(TargetType.CASE, c.getId());
        return new Evidence(c.getCaseNo(), CaseTransaction.of(t, c.getAccountKey(), TransactionScope.EVIDENCE), true);
    }

    /** C5. 시각·id 순으로 limit 건. */
    @Transactional(readOnly = true)
    public Graph graph(String caseNo, int limit) {
        Case c = load(caseNo);
        ListAlertTransactions.Page rows = alertTransactions.page(c.getAlertId(), AlertTransactionOrder.TS_ASC, 0, limit);
        Map<String, Boolean> nodes = new LinkedHashMap<>();
        List<Edge> edges = rows.items().stream().map(t -> {
            nodes.putIfAbsent(t.fromAccountKey(), true);
            nodes.putIfAbsent(t.toAccountKey(), true);
            return new Edge(t.transactionId(), t.fromAccountKey(), t.toAccountKey(), t.amountReceived(),
                    t.currencyReceived(), TransactionScope.EVIDENCE, t.ts());
        }).toList();
        return new Graph(List.copyOf(nodes.keySet()), edges, rows.total());
    }

    /** 상세 aggregates 용 — 부르는 쪽 트랜잭션 안에서. */
    CaseAggregates aggregatesOf(Case c) {
        return CaseAggregates.of(alertTransactions.all(c.getAlertId()).stream()
                .map(t -> CaseTransaction.of(t, c.getAccountKey(), TransactionScope.EVIDENCE)).toList());
    }

    private Case load(String caseNo) {
        return cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
    }
}
