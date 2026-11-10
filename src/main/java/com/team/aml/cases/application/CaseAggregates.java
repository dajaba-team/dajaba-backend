package com.team.aml.cases.application;

import com.team.aml.cases.domain.TransactionDirection;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 상세 aggregates (CASES 명세 API-02, 계약서 C2). 경보의 근거 거래 전체로 계산한다 — 미리보기·그래프 건수가 아님.
 * 유입 = 대상 계좌가 받은 거래의 amount_received(통화별), 유출 = 보낸 거래의 amount_paid(통화별).
 * 경유 거래(INDIRECT)와 자기계좌 거래는 유입·유출에 넣지 않는다. 통화가 다르면 더하지 않는다(환산 없음).
 * transaction_count·first_ts·last_ts 는 근거 거래 전체 기준(경유 포함).
 */
public record CaseAggregates(int transactionCount, Side inbound, Side outbound, LocalDateTime firstTs,
                             LocalDateTime lastTs) {

    public record Side(int transactionCount, List<Total> totals) {
        public Side {
            totals = List.copyOf(totals);
        }
    }

    public record Total(String currency, BigDecimal amount) {
    }

    static CaseAggregates of(List<CaseTransaction> evidence) {
        Map<String, BigDecimal> in = new TreeMap<>();
        Map<String, BigDecimal> out = new TreeMap<>();
        int inCount = 0;
        int outCount = 0;
        LocalDateTime first = null;
        LocalDateTime last = null;
        for (CaseTransaction t : evidence) {
            first = first == null || t.ts().isBefore(first) ? t.ts() : first;
            last = last == null || t.ts().isAfter(last) ? t.ts() : last;
            if (t.selfTransfer()) {
                continue;
            }
            if (t.direction() == TransactionDirection.INBOUND) {
                in.merge(t.currencyReceived(), t.amountReceived(), BigDecimal::add);
                inCount++;
            } else if (t.direction() == TransactionDirection.OUTBOUND) {
                out.merge(t.currencyPaid(), t.amountPaid(), BigDecimal::add);
                outCount++;
            }
        }
        return new CaseAggregates(evidence.size(), side(inCount, in), side(outCount, out), first, last);
    }

    private static Side side(int count, Map<String, BigDecimal> byCurrency) {
        return new Side(count, byCurrency.entrySet().stream().map(e -> new Total(e.getKey(), e.getValue())).toList());
    }
}
