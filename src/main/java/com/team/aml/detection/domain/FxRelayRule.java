package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * R03_FX_RELAY 판정 (룰 v2, rules.md R03). 중계 쌍 찾기는 SQL(RuleScanRepository), 거짓→참 판정과 쌍 검산은 여기.
 * 중계 쌍 = 계좌 M 이 받은 거래 a 와, a 보다 늦은 분에 M 이 보낸 거래 b (둘 다 창 안, 자기 계좌 거래 제외).
 * require_currency_change 면 b 보낸 통화 ≠ a 받은 통화. USD 환산 비율
 * (b 보낸 금액 ÷ rate[b 통화]) ÷ (a 받은 금액 ÷ rate[a 통화]) 이 [1 - tolerance, 1 + tolerance] 안(양 끝 포함).
 * 비율은 나누지 않고 b금액 × rate[a] 와 a금액 × rate[b] × (1 ± tolerance) 를 곱셈으로 비교한다 — SQL·Java 가 반올림 없이 같은 답.
 * 표에 없는 통화가 끼면 그 쌍은 평가하지 않는다. E = 쌍을 완성하는 출금 b 의 분, 1분 전 창 [E-H, E) 에 쌍이 없을 때만 경보(B7).
 */
public record FxRelayRule(int windowHours, BigDecimal amountTolerance, boolean requireCurrencyChange,
                          Map<String, BigDecimal> usdRates) {

    public static final String RULE_ID = "R03_FX_RELAY";
    static final String WINDOW = "window_hours";
    static final String TOLERANCE = "amount_tolerance";
    static final String CURRENCY_CHANGE = "require_currency_change";
    static final String USD_RATES = "usd_rates";

    public FxRelayRule {
        if (windowHours < 1) {
            throw new InvalidRuleParamsException(RULE_ID, WINDOW);
        }
        if (amountTolerance == null || amountTolerance.signum() < 0 || amountTolerance.compareTo(BigDecimal.ONE) >= 0) {
            throw new InvalidRuleParamsException(RULE_ID, TOLERANCE);
        }
        if (usdRates == null || usdRates.isEmpty()
                || usdRates.values().stream().anyMatch(r -> r == null || r.signum() <= 0)) {
            throw new InvalidRuleParamsException(RULE_ID, USD_RATES);
        }
        usdRates = Map.copyOf(usdRates);
    }

    /** rules.params 에서 넷 다 읽는다. 없거나 형식이 틀리면 INVALID_RULE_PARAMS — 기본값으로 돌지 않는다. */
    public static FxRelayRule from(JsonNode params) {
        return new FxRelayRule(RuleParams.requiredInt(params, RULE_ID, WINDOW),
                RuleParams.requiredDecimal(params, RULE_ID, TOLERANCE),
                RuleParams.requiredBoolean(params, RULE_ID, CURRENCY_CHANGE),
                RuleParams.requiredDecimalMap(params, RULE_ID, USD_RATES));
    }

    /** 이 분에 거짓→참이 됐나. SQL 은 E 에 끝나는 쌍이 있는 분만 준다 — 1분 전 창에 쌍이 없을 때만 참으로 바뀐 것. */
    public boolean fires(FxRelayMinute m) {
        return !m.pairBefore();
    }

    /** 한 쌍의 통화·금액 조건 (저장 전 검산). 계좌·시각·창은 FxRelayEvidence 가 본다. */
    public boolean relays(RelayPair p) {
        BigDecimal inRate = usdRates.get(p.inCurrency());
        BigDecimal outRate = usdRates.get(p.outCurrency());
        if (inRate == null || outRate == null || p.inAmount().signum() <= 0) {
            return false;
        }
        if (requireCurrencyChange && p.inCurrency().equals(p.outCurrency())) {
            return false;
        }
        BigDecimal out = p.outAmount().multiply(inRate);
        BigDecimal in = p.inAmount().multiply(outRate);
        return out.compareTo(in.multiply(BigDecimal.ONE.subtract(amountTolerance))) >= 0
                && out.compareTo(in.multiply(BigDecimal.ONE.add(amountTolerance))) <= 0;
    }

    /** rule_facts relays.ratio — USD 환산 비율(보낸 ÷ 받은), 소수 6자리. 판정은 relays() 의 곱셈 비교로 한다. */
    public BigDecimal ratio(RelayPair p) {
        return p.outAmount().multiply(usdRates.get(p.inCurrency()))
                .divide(p.inAmount().multiply(usdRates.get(p.outCurrency())), 6, RoundingMode.HALF_EVEN);
    }

    /** 창 끝 = reference_ts = E+1분. 뜻은 (E-H, E] — 다른 룰과 같다. */
    public LocalDateTime windowEnd(LocalDateTime minute) {
        return minute.plusMinutes(1);
    }

    public LocalDateTime windowStart(LocalDateTime minute) {
        return windowEnd(minute).minusHours(windowHours);
    }
}
