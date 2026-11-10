package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseTransactions;
import com.team.aml.cases.domain.TransactionScope;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 자금 흐름 그래프 (CASES 명세 API-10, 계약서 C5). 노드는 account_ref 로 중복 제거, 선은 거래마다 하나.
 * truncated 면 화면은 "전체 N건 중 M건" — 미리보기 건수로 합계를 내지 않는다.
 */
public record CaseGraphResponse(List<Node> nodes, List<Edge> edges, int returnedTransactionCount,
                                long totalTransactionCount, boolean truncated) {

    public record Node(String accountRef, String accountNo) {
    }

    public record Edge(long transactionId, String source, String target, BigDecimal amount, String currency,
                       String amountBasis, TransactionScope scope, LocalDateTime ts) {
    }

    static CaseGraphResponse from(CaseTransactions.Graph g) {
        return new CaseGraphResponse(
                g.nodeAccountKeys().stream().map(k -> new Node(AccountDisplay.ref(k), AccountDisplay.maskedNo(k))).toList(),
                g.edges().stream().map(e -> new Edge(e.transactionId(), AccountDisplay.ref(e.sourceAccountKey()),
                        AccountDisplay.ref(e.targetAccountKey()), e.amount(), e.currency(), "RECEIVED", e.scope(),
                        e.ts())).toList(),
                g.edges().size(), g.totalTransactionCount(), g.truncated());
    }
}
