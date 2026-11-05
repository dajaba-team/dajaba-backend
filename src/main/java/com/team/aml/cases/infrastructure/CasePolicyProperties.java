package com.team.aml.cases.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * aml.case-policy.* — 운영 등급 경계값 (CasePolicy, 명세 §7.1). 기본값을 두지 않는다(협의 전).
 * 비어 있어도 앱은 뜨고, 케이스 생성 배치를 시작할 때 POLICY_NOT_CONFIGURED 로 거절한다.
 */
@ConfigurationProperties(prefix = "aml.case-policy")
public record CasePolicyProperties(String version, BigDecimal lowToMedium, BigDecimal mediumToHigh) {
}
