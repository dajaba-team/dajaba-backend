package com.team.aml.cases.domain;

/**
 * 지금 적용할 운영 등급 정책을 준다 (안건 2·미결 23). 경계값은 평가 뒤 확정이라 설정(aml.case-policy.*)으로 받고,
 * 구현은 infrastructure 에 둔다. 값이 없거나 잘못되면 LOW 로 대신하지 않고 PolicyNotConfiguredException.
 */
public interface CasePolicySource {
    CasePolicy current();
}
