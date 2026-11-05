package com.team.aml.archfixture.aml.user.api;

import com.team.aml.cases.application.AttachDraftToCase;

/** ArchitectureRulesTest 전용. 실행되지 않고 ArchUnit 이 바이트코드만 읽는다. */
public final class UserApiFixtures {

    private UserApiFixtures() {}

    /** ④ 위반 — user 의 api 도 다른 기능을 못 쓴다 */
    public static class UsesOtherFeature {
        AttachDraftToCase useCase;
    }
}
