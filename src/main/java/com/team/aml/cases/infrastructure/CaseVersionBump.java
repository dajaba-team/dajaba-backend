package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.Case;

/** Spring Data 커스텀 조각 — 구현은 CaseVersionBumpImpl (이름 규칙: 인터페이스 이름 + Impl). */
public interface CaseVersionBump {
    void bumpVersion(Case c);
}
