package com.team.aml.source.domain;

/** 패턴 파일의 거래 한 행. 값은 파일 원문 그대로이고 정규화는 거래 적재에서 한다.
    원본 문자열 그대로 비교하려는 목적이기 때문에 String으로 저장한다. */
public record PatternRow(
        String patternId,
        PatternType patternType,
        String timestampText,
        String fromBank,
        String fromAccount,
        String toBank,
        String toAccount,
        String amountReceived,
        String receivingCurrency,
        String amountPaid,
        String paymentCurrency,
        String paymentFormat,
        String isLaundering) {

    /** 계좌·금액이 로그에 남지 않도록 ID와 유형만 보여 준다. */
    @Override
    public String toString() {
        return "PatternRow[" + patternId + ", " + patternType + "]";
    }
}
