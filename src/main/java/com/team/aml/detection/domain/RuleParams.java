package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** rules.params 읽기. 없거나 형식이 틀리면 INVALID_RULE_PARAMS — 코드에 기본값을 두지 않는다 (SCRUM-99). */
final class RuleParams {

    private RuleParams() {
    }

    /** int 범위의 정수. 범위(1 이상 등)는 룰 record 생성자가 본다. */
    static int requiredInt(JsonNode params, String ruleId, String key) {
        JsonNode n = params == null ? null : params.get(key);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt()) {
            throw new InvalidRuleParamsException(ruleId, key);
        }
        return n.intValue();
    }

    /** 숫자(정수·소수). 범위는 룰 record 생성자가 본다. */
    static BigDecimal requiredDecimal(JsonNode params, String ruleId, String key) {
        JsonNode n = params == null ? null : params.get(key);
        if (n == null || !n.isNumber()) {
            throw new InvalidRuleParamsException(ruleId, key);
        }
        return n.decimalValue();
    }

    static boolean requiredBoolean(JsonNode params, String ruleId, String key) {
        JsonNode n = params == null ? null : params.get(key);
        if (n == null || !n.isBoolean()) {
            throw new InvalidRuleParamsException(ruleId, key);
        }
        return n.booleanValue();
    }

    /** {이름: 숫자} 객체 (R03 usd_rates). 값이 숫자가 아니면 INVALID_RULE_PARAMS. 비었는지·양수인지는 룰 record 가 본다. */
    static Map<String, BigDecimal> requiredDecimalMap(JsonNode params, String ruleId, String key) {
        JsonNode n = params == null ? null : params.get(key);
        if (n == null || !n.isObject()) {
            throw new InvalidRuleParamsException(ruleId, key);
        }
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isNumber()) {
                throw new InvalidRuleParamsException(ruleId, key);
            }
            map.put(e.getKey(), e.getValue().decimalValue());
        }
        return map;
    }
}
