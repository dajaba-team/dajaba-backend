package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.CasePolicy;
import com.team.aml.cases.domain.CasePolicySource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 설정값으로 CasePolicy 를 만든다. 부를 때마다 검사해서, 설정이 비어 있어도 앱 기동은 막지 않는다. */
@Component
@EnableConfigurationProperties(CasePolicyProperties.class)
class ConfiguredCasePolicySource implements CasePolicySource {

    private final CasePolicyProperties properties;

    ConfiguredCasePolicySource(CasePolicyProperties properties) {
        this.properties = properties;
    }

    @Override
    public CasePolicy current() {
        return CasePolicy.of(properties.version(), properties.lowToMedium(), properties.mediumToHigh());
    }
}
