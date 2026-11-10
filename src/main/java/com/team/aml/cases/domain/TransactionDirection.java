package com.team.aml.cases.domain;

import java.util.Objects;

/**
 * 케이스 계좌(경보 계좌) 기준 거래 방향 (계약서 C3, 11/4 Q12). 판단은 여기 한 곳.
 * INDIRECT = 경유 거래 — 대상 계좌가 보내지도 받지도 않은 거래(CYCLE·STACK 중간). 유입·유출 합계에 넣지 않는다.
 * 자기계좌 거래(보낸·받은 계좌가 둘 다 대상 계좌)는 받은 쪽으로 INBOUND 지만 합계에서는 뺀다(CASES 명세 API-02).
 * 〔미결 22: 자기계좌 거래 표시를 따로 둘지는 미정 — 지금은 enum 값을 늘리지 않는다〕
 */
public enum TransactionDirection {
    INBOUND, OUTBOUND, INDIRECT;

    public static TransactionDirection of(String caseAccountKey, String fromAccountKey, String toAccountKey) {
        if (Objects.equals(caseAccountKey, toAccountKey)) {
            return INBOUND;
        }
        if (Objects.equals(caseAccountKey, fromAccountKey)) {
            return OUTBOUND;
        }
        return INDIRECT;
    }
}
