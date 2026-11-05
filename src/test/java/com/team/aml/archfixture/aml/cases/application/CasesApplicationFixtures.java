package com.team.aml.archfixture.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.str.domain.StrDraft;
import org.springframework.transaction.annotation.Transactional;

/** ArchitectureRulesTest 전용. 실행되지 않고 ArchUnit 이 바이트코드만 읽는다. */
public final class CasesApplicationFixtures {

    private CasesApplicationFixtures() {}

    /** ② 정상 — application 의 클래스·메서드 */
    @Transactional
    public static class TransactionalInApplication {
        @Transactional(readOnly = true)
        public void read() {}

        @jakarta.transaction.Transactional
        public void write() {}
    }

    /** ③ 정상 — 자기 기능 application */
    public static class CallsCaseFromOwnApplication {
        void run(Case c) {
            c.attachDraft(1L, 1L);
        }
    }

    /** ③ 위반 — 다른 기능의 StrDraft, 확인만 하는 메서드도 포함 */
    public static class CallsStrDraftFromOtherFeature {
        void run(StrDraft d) {
            d.requireActive();
        }
    }
}
