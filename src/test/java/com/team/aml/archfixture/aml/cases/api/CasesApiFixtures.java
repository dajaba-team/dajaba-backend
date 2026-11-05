package com.team.aml.archfixture.aml.cases.api;

import com.team.aml.cases.application.AttachDraftToCase;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.detection.application.FindAlertsForCaseOpening;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.user.domain.Role;

/** ArchitectureRulesTest 전용. 실행되지 않고 ArchUnit 이 바이트코드만 읽는다. 패키지가 `..aml.cases.api..`에 걸리게 둔다. */
public final class CasesApiFixtures {

    private CasesApiFixtures() {}

    /** ① 위반 */
    public static class UsesRepository {
        CaseRepository repository;
    }

    /** ①·④ 정상 — 자기 기능 application */
    public static class UsesOwnApplication {
        AttachDraftToCase useCase;
    }

    /** ④ 위반 — 다른 기능 application */
    public static class UsesOtherFeature {
        FindAlertsForCaseOpening useCase;
    }

    /** ④ 정상 — user·shared 는 허용 */
    public static class UsesUserAndShared {
        Role role;
        CurrentUser currentUser;
    }

    /** ② 위반 — 클래스에 붙인 jakarta */
    @jakarta.transaction.Transactional
    public static class JakartaOnClass {
    }

    /** ② 위반 — 메서드에 붙인 jakarta */
    public static class JakartaOnMethod {
        @jakarta.transaction.Transactional
        public void run() {}
    }

    /** ③ 위반 — 자기 기능이어도 api 에서는 못 부른다 */
    public static class CallsCaseFromOwnApi {
        void run(Case c) {
            c.assign(1L, 1L);
        }
    }
}
