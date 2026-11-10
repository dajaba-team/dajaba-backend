package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** 켜진 룰 한 버전 (rules, is_active). refId 는 rules.id — 경보의 rule_ref_id. */
public record ActiveRule(long refId, String ruleId, int version, String typologyHint, JsonNode params) {
}
