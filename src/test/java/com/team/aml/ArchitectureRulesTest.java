package com.team.aml;

import com.team.aml.archfixture.aml.cases.api.CasesApiFixtures;
import com.team.aml.archfixture.aml.cases.application.CasesApplicationFixtures;
import com.team.aml.archfixture.aml.cases.infrastructure.CasesInfrastructureFixtures;
import com.team.aml.archfixture.aml.str.application.StrApplicationFixtures;
import com.team.aml.archfixture.aml.str.infrastructure.StrInfrastructureFixtures;
import com.team.aml.archfixture.aml.user.api.UserApiFixtures;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArchitectureTest 의 SCRUM-94 규칙이 실제로 잡는지 가짜 클래스(archfixture)로 확인한다. DB 없이 돈다.
 * 규칙이 비어 있거나 패턴이 틀리면 "통과"만 보고는 모르기 때문에 위반 쪽도 같이 본다.
 */
class ArchitectureRulesTest {

    private static final List<String> TRANSACTIONAL_EXCEPTIONS = List.of(
            "com.team.aml.shared.audit.AccessLogWriter",
            "com.team.aml.shared.audit.LoginAccessLogWriter",
            "com.team.aml.shared.ai.AiCallLogWriter",
            "com.team.aml.user.infrastructure.DbUserDetailsService",
            "com.team.aml.user.infrastructure.DevUserSeeder");

    // --- ① api → *Repository ---------------------------------------------------

    @Test
    void 규칙1_위반_api가_Repository에_의존() {
        assertCaught(ArchitectureTest.apiRepositoryRules(), CasesApiFixtures.UsesRepository.class);
    }

    @Test
    void 규칙1_정상_api가_자기_기능_application에_의존() {
        assertPasses(ArchitectureTest.apiRepositoryRules(), CasesApiFixtures.UsesOwnApplication.class);
    }

    // --- ② @Transactional --------------------------------------------------------

    @Test
    void 규칙2_위반_클래스에_붙인_Spring() {
        assertCaught(ArchitectureTest.transactionalRules(), CasesInfrastructureFixtures.SpringOnClass.class);
    }

    @Test
    void 규칙2_위반_메서드에_붙인_Spring() {
        assertCaught(ArchitectureTest.transactionalRules(), CasesInfrastructureFixtures.SpringOnMethod.class);
    }

    @Test
    void 규칙2_위반_클래스에_붙인_jakarta() {
        assertCaught(ArchitectureTest.transactionalRules(), CasesApiFixtures.JakartaOnClass.class);
    }

    @Test
    void 규칙2_위반_메서드에_붙인_jakarta() {
        assertCaught(ArchitectureTest.transactionalRules(), CasesApiFixtures.JakartaOnMethod.class);
    }

    @Test
    void 규칙2_위반_예외와_이름만_같은_클래스() {
        assertCaught(ArchitectureTest.transactionalRules(), CasesInfrastructureFixtures.DevUserSeeder.class);
    }

    @Test
    void 규칙2_정상_application의_클래스와_메서드() {
        assertPasses(ArchitectureTest.transactionalRules(), CasesApplicationFixtures.TransactionalInApplication.class);
    }

    @Test
    void 규칙2_정상_예외_5개가_실제로_통과하고_실제로_붙어_있다() {
        JavaClasses main = ArchitectureTest.mainClasses();
        for (String name : TRANSACTIONAL_EXCEPTIONS) {
            JavaClass c = main.get(name);
            // 어노테이션이 없어졌으면 예외 목록에서 빼야 한다
            assertThat(c.getMethods()).as(name).anyMatch(m -> m.isAnnotatedWith(org.springframework.transaction.annotation.Transactional.class));
            assertThat(violations(ArchitectureTest.transactionalRules(), main.that(com.tngtech.archunit.base.DescribedPredicate.describe(name, j -> j.getName().equals(name))))).as(name).isEmpty();
        }
    }

    // --- ③ Case·StrDraft 상태 변경 ---------------------------------------------------

    @Test
    void 규칙3_위반_다른_기능에서_메서드_호출() {
        assertCaught(ArchitectureTest.caseStrDraftRules(), StrApplicationFixtures.CallsCaseFromOtherFeature.class);
    }

    @Test
    void 규칙3_위반_다른_기능에서_메서드_참조() {
        assertCaught(ArchitectureTest.caseStrDraftRules(), StrApplicationFixtures.ReferencesCaseFromOtherFeature.class);
    }

    @Test
    void 규칙3_위반_자기_기능_api에서_호출() {
        assertCaught(ArchitectureTest.caseStrDraftRules(), CasesApiFixtures.CallsCaseFromOwnApi.class);
    }

    @Test
    void 규칙3_위반_다른_기능에서_static_팩토리() {
        assertCaught(ArchitectureTest.caseStrDraftRules(), StrApplicationFixtures.CallsCaseFactoryFromOtherFeature.class);
    }

    @Test
    void 규칙3_위반_다른_기능에서_StrDraft_확인_메서드() {
        assertCaught(ArchitectureTest.caseStrDraftRules(), CasesApplicationFixtures.CallsStrDraftFromOtherFeature.class);
    }

    @Test
    void 규칙3_정상_같은_기능_infrastructure에서_호출() {
        assertPasses(ArchitectureTest.caseStrDraftRules(), CasesInfrastructureFixtures.CallsCaseFromOwnInfrastructure.class);
        assertPasses(ArchitectureTest.caseStrDraftRules(), StrInfrastructureFixtures.CallsStrDraftFromOwnInfrastructure.class);
    }

    @Test
    void 규칙3_정상_자기_기능_application에서_호출() {
        assertPasses(ArchitectureTest.caseStrDraftRules(), CasesApplicationFixtures.CallsCaseFromOwnApplication.class);
        assertPasses(ArchitectureTest.caseStrDraftRules(), StrApplicationFixtures.CallsStrDraftFromOwnApplication.class);
    }

    @Test
    void 규칙3_정상_getter는_다른_기능에서도_읽는다() {
        assertPasses(ArchitectureTest.caseStrDraftRules(), StrApplicationFixtures.ReadsCaseGetters.class);
    }

    // --- ④ api → 다른 기능 --------------------------------------------------------

    @Test
    void 규칙4_위반_api가_다른_기능에_의존() {
        assertCaught(ArchitectureTest.apiOtherFeatureRules(), CasesApiFixtures.UsesOtherFeature.class);
    }

    @Test
    void 규칙4_위반_user의_api도_다른_기능에_의존_못_한다() {
        assertCaught(ArchitectureTest.apiOtherFeatureRules(), UserApiFixtures.UsesOtherFeature.class);
    }

    @Test
    void 규칙4_정상_api가_user와_shared를_쓴다() {
        assertPasses(ArchitectureTest.apiOtherFeatureRules(), CasesApiFixtures.UsesUserAndShared.class);
    }

    @Test
    void 규칙4_정상_api가_자기_기능을_쓴다() {
        assertPasses(ArchitectureTest.apiOtherFeatureRules(), CasesApiFixtures.UsesOwnApplication.class);
    }

    // --- 본 검사 범위 --------------------------------------------------------------

    @Test
    void 본_검사에는_archfixture와_테스트_클래스가_안_들어간다() {
        JavaClasses main = ArchitectureTest.mainClasses();

        assertThat(main.contain("com.team.aml.AmlApplication")).isTrue();
        assertThat(main.stream().map(JavaClass::getName)).noneMatch(n -> n.contains(".archfixture."));
        assertThat(main.contain(ArchitectureTest.class)).isFalse();
        // 가짜 클래스가 없어서 통과한 게 아님을 확인
        assertThat(new ClassFileImporter().withImportOption(new ImportOption.OnlyIncludeTests())
                .importPackages("com.team.aml.archfixture").contain(CasesApiFixtures.UsesRepository.class)).isTrue();
    }

    // --- 도우미 --------------------------------------------------------------------

    private static void assertCaught(List<ArchRule> rules, Class<?> fixture) {
        assertThat(violations(rules, new ClassFileImporter().importClasses(fixture)))
                .as(fixture.getSimpleName()).anyMatch(detail -> detail.contains(fixture.getName()));
    }

    private static void assertPasses(List<ArchRule> rules, Class<?> fixture) {
        assertThat(violations(rules, new ClassFileImporter().importClasses(fixture))).as(fixture.getSimpleName()).isEmpty();
    }

    private static List<String> violations(List<ArchRule> rules, JavaClasses classes) {
        return rules.stream().flatMap(r -> r.evaluate(classes).getFailureReport().getDetails().stream()).toList();
    }
}
