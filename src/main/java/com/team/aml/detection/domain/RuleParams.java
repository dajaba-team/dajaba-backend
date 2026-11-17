package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

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
}
