package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * R06_GATHER_SCATTER 판정 (B7 11/2, 룰 v2). 집계는 SQL(RuleScanRepository), 판정만 여기.
 * 대상 계좌에 입금이나 출금이 있었던 분 E 마다 [E+1분-H, E+1분) 의 서로 다른 송신 계좌 수·수신 계좌 수를 본다.
 * 둘 다 [하한, 상한] 안(양 끝 포함)이면 참. 1분 전 창 [E-H, E) 이 거짓일 때만 경보 1건(거짓→참).
 * 상한은 입금 상대·출금 상대 각각에 건다(합계 아님). 입금·출금 선후는 보지 않는다. 자기 계좌 거래는 SQL 에서 뺀다.
 * 만료만으로 참이 된 분은 거래가 없어 평가하지 않는다(rules.md 1.2, 노션 v2 3.1 — 오프라인 분석과 같다).
 */
public record GatherScatterRule(int windowHours, int minSenders, int maxSenders, int minReceivers, int maxReceivers) {

    public static final String RULE_ID = "R06_GATHER_SCATTER";
    static final String WINDOW = "window_hours";
    static final String MIN_SENDERS = "min_distinct_senders";
    static final String MAX_SENDERS = "max_distinct_senders";
    static final String MIN_RECEIVERS = "min_distinct_receivers";
    static final String MAX_RECEIVERS = "max_distinct_receivers";

    public GatherScatterRule {
        if (windowHours < 1) {
            throw new InvalidRuleParamsException(RULE_ID, WINDOW);
        }
        if (minSenders < 1) {
            throw new InvalidRuleParamsException(RULE_ID, MIN_SENDERS);
        }
        if (maxSenders < minSenders) {
            throw new InvalidRuleParamsException(RULE_ID, MAX_SENDERS);
        }
        if (minReceivers < 1) {
            throw new InvalidRuleParamsException(RULE_ID, MIN_RECEIVERS);
        }
        if (maxReceivers < minReceivers) {
            throw new InvalidRuleParamsException(RULE_ID, MAX_RECEIVERS);
        }
    }

    /** rules.params 에서 다섯 개 다 읽는다. 없거나 정수가 아니거나 하한 < 1·상한 < 하한이면 INVALID_RULE_PARAMS. */
    public static GatherScatterRule from(JsonNode params) {
        return new GatherScatterRule(RuleParams.requiredInt(params, RULE_ID, WINDOW),
                RuleParams.requiredInt(params, RULE_ID, MIN_SENDERS),
                RuleParams.requiredInt(params, RULE_ID, MAX_SENDERS),
                RuleParams.requiredInt(params, RULE_ID, MIN_RECEIVERS),
                RuleParams.requiredInt(params, RULE_ID, MAX_RECEIVERS));
    }

    /** 이 분에 거짓→참이 됐나. */
    public boolean fires(GatherScatterMinute m) {
        return holds(m.sendersNow(), m.receiversNow()) && !holds(m.sendersBefore(), m.receiversBefore());
    }

    boolean holds(int senders, int receivers) {
        return senders >= minSenders && senders <= maxSenders
                && receivers >= minReceivers && receivers <= maxReceivers;
    }

    /** 창 끝 = reference_ts = E+1분. 뜻은 (E-H, E] — R01·R02 와 같다. */
    public LocalDateTime windowEnd(LocalDateTime minute) {
        return minute.plusMinutes(1);
    }

    public LocalDateTime windowStart(LocalDateTime minute) {
        return windowEnd(minute).minusHours(windowHours);
    }
}
