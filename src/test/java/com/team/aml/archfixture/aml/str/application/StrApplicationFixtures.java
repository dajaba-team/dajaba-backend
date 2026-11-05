package com.team.aml.archfixture.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.str.domain.StrDraft;

/** ArchitectureRulesTest 전용. 실행되지 않고 ArchUnit 이 바이트코드만 읽는다. */
public final class StrApplicationFixtures {

    private StrApplicationFixtures() {}

    /** ③ 위반 — 다른 기능에서 메서드 호출 */
    public static class CallsCaseFromOtherFeature {
        void run(Case c) {
            c.attachDraft(1L, 1L);
        }
    }

    /** ③ 위반 — 다른 기능에서 메서드 참조 */
    public static class ReferencesCaseFromOtherFeature {
        interface Escalate {
            void run(Case c, Long actorId, String comment);
        }

        Escalate escalate() {
            return Case::escalate;
        }
    }

    /** ③ 위반 — 다른 기능에서 static 팩토리 */
    public static class CallsCaseFactoryFromOtherFeature {
        Case run() {
            return Case.open("CASE-2022-000001", 1L, "a", "e", 1L);
        }
    }

    /** ③ 정상 — getter 는 어디서나 */
    public static class ReadsCaseGetters {
        void run(Case c) {
            c.getStatus();
            c.isRejectedForRevision();
        }
    }

    /** ③ 정상 — 자기 기능 application */
    public static class CallsStrDraftFromOwnApplication {
        void run(StrDraft d) {
            d.lock();
        }
    }
}
