package com.team.aml.archfixture.aml.cases.infrastructure;

import com.team.aml.cases.domain.Case;
import org.springframework.transaction.annotation.Transactional;

/** ArchitectureRulesTest 전용. 실행되지 않고 ArchUnit 이 바이트코드만 읽는다. */
public final class CasesInfrastructureFixtures {

    private CasesInfrastructureFixtures() {}

    /** ② 위반 — 클래스에 붙인 Spring */
    @Transactional
    public static class SpringOnClass {
    }

    /** ② 위반 — 메서드에 붙인 Spring */
    public static class SpringOnMethod {
        @Transactional
        public void run() {}
    }

    /** ② 위반 — 예외와 이름만 같은 클래스. 예외는 패키지까지 맞아야 한다 */
    public static class DevUserSeeder {
        @Transactional
        public void run() {}
    }

    /** ③ 정상 — 같은 기능 저장소 구현 */
    public static class CallsCaseFromOwnInfrastructure {
        void run(Case c) {
            c.takePendingHistories();
        }
    }
}
