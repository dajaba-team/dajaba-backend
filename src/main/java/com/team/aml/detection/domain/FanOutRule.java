package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * R01_FAN_OUT 판정 (B7 11/2, 룰 v2). 집계는 SQL(RuleScanRepository), 판정만 여기.
 * 대상 계좌가 송금한 분 E 마다 [E+1분-H, E+1분) 의 서로 다른 수신 계좌 수를 본다(H = window_hours).
 * N 이상이면서 1분 전 창 [E-H, E) 은 N 미만일 때(거짓→참)만 경보 1건. 참이 이어지는 동안은 더 만들지 않는다.
 * 자기 계좌 거래는 세지 않는다(SQL 에서 뺀다).
 */
public record FanOutRule(int windowHours, int minDistinctReceivers) {

    public static final String RULE_ID = "R01_FAN_OUT";
    static final String WINDOW = "window_hours";
    static final String MIN_RECEIVERS = "min_distinct_receivers";

    public FanOutRule {
        if (windowHours < 1) {
            throw new InvalidRuleParamsException(RULE_ID, WINDOW);
        }
        if (minDistinctReceivers < 1) {
            throw new InvalidRuleParamsException(RULE_ID, MIN_RECEIVERS);
        }
    }

    /** rules.params 에서 둘 다 읽는다. 없거나 정수가 아니거나 1 미만이면 INVALID_RULE_PARAMS — 기본값으로 돌지 않는다. */
    public static FanOutRule from(JsonNode params) {
        return new FanOutRule(RuleParams.requiredInt(params, RULE_ID, WINDOW),
                RuleParams.requiredInt(params, RULE_ID, MIN_RECEIVERS));
    }

    /** 이 분에 거짓→참이 됐나. */
    public boolean fires(FanMinute m) {
        return m.countNow() >= minDistinctReceivers && m.countBefore() < minDistinctReceivers;
    }

    /** 창 끝 = reference_ts = E+1분. 뜻은 (E-H, E] — R02 와 같다. */
    public LocalDateTime windowEnd(LocalDateTime minute) {
        return minute.plusMinutes(1);
    }

    public LocalDateTime windowStart(LocalDateTime minute) {
        return windowEnd(minute).minusHours(windowHours);
    }
}
