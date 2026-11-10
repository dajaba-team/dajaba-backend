package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * R02_FAN_IN 판정 (B7 11/2, 룰 v2). 집계는 SQL(RuleScanRepository), 판정만 여기.
 * 대상 계좌에 입금이 있었던 분 E 마다 [E+1분-H, E+1분) 의 서로 다른 송금 계좌 수를 본다(H = window_hours).
 * N 이상이면서 1분 전 창 [E-H, E) 은 N 미만일 때(거짓→참)만 경보 1건. 참이 이어지는 동안은 더 만들지 않는다.
 * 자기 계좌 거래는 세지 않는다(SQL 에서 뺀다).
 */
public record FanInRule(int windowHours, int minDistinctSenders) {

    public static final String RULE_ID = "R02_FAN_IN";
    static final String WINDOW = "window_hours";
    static final String MIN_SENDERS = "min_distinct_senders";

    public FanInRule {
        if (windowHours < 1) {
            throw new InvalidRuleParamsException(RULE_ID, WINDOW);
        }
        if (minDistinctSenders < 1) {
            throw new InvalidRuleParamsException(RULE_ID, MIN_SENDERS);
        }
    }

    /** rules.params 에서 둘 다 읽는다. 없거나 정수가 아니거나 1 미만이면 INVALID_RULE_PARAMS — 기본값으로 돌지 않는다. */
    public static FanInRule from(JsonNode params) {
        return new FanInRule(intParam(params, WINDOW), intParam(params, MIN_SENDERS));
    }

    private static int intParam(JsonNode params, String key) {
        JsonNode n = params == null ? null : params.get(key);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt()) {
            throw new InvalidRuleParamsException(RULE_ID, key);
        }
        return n.intValue();
    }

    /** 이 분에 거짓→참이 됐나. */
    public boolean fires(FanInMinute m) {
        return m.sendersNow() >= minDistinctSenders && m.sendersBefore() < minDistinctSenders;
    }

    /** 창 끝 = reference_ts = E+1분. 원천 시각이 분 단위라 뜻은 (E-H, E] — 지금 분은 넣고 정확히 H 전 그 분은 뺀다. */
    public LocalDateTime windowEnd(LocalDateTime minute) {
        return minute.plusMinutes(1);
    }

    public LocalDateTime windowStart(LocalDateTime minute) {
        return windowEnd(minute).minusHours(windowHours);
    }
}
