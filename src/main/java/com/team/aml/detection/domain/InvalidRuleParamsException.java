package com.team.aml.detection.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/** 409 INVALID_RULE_PARAMS — rules.params 에 기준값이 없거나 잘못됐다. 기본값으로 조용히 돌지 않는다. */
public class InvalidRuleParamsException extends DomainException {
    public InvalidRuleParamsException(String ruleId, String param) {
        super(ruleId + " 기준값 " + param + " 이 없거나 잘못됐습니다", Map.of("rule_id", ruleId, "param", param));
    }
}
