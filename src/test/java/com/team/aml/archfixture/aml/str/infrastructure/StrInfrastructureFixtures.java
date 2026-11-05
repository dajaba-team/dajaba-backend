package com.team.aml.archfixture.aml.str.infrastructure;

import com.team.aml.str.domain.StrDraft;

/** ArchitectureRulesTest 전용. 실행되지 않고 ArchUnit 이 바이트코드만 읽는다. */
public final class StrInfrastructureFixtures {

    private StrInfrastructureFixtures() {}

    /** ③ 정상 — 같은 기능 저장소 구현 */
    public static class CallsStrDraftFromOwnInfrastructure {
        void run(StrDraft d) {
            d.currentBody();
        }
    }
}
