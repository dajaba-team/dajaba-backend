package com.team.aml;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.conditions.ArchConditions.accessTargetWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 폴더 규칙을 CI 가 잡는다 (레퍼런스 4.1, 4.2, 6.1). DB 없이 돈다.
 *  1) 기능 간 의존 방향: source ← detection ← cases ← str. user 는 모두가 읽기만. shared 는 기능을 모른다.
 *  2) 계층: api → application → domain ← infrastructure.
 *  3) domain 은 Spring 을 import 하지 않는다 (JPA·Spring Data 애노테이션은 허용).
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = mainClasses();
    }

    /** 본 검사가 읽는 클래스. 테스트 클래스·가짜 클래스(archfixture)는 안 들어간다. */
    static JavaClasses mainClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.team.aml");
    }

    @Test
    void 기능_간_의존_방향() {
        noClasses().that().resideInAPackage("..aml.source..")
                .should().dependOnClassesThat().resideInAnyPackage("..aml.detection..", "..aml.cases..", "..aml.str..")
                .allowEmptyShould(true)   // 아직 빈 기능 폴더는 통과, 클래스가 생기면 그대로 검사
                .check(classes);
        noClasses().that().resideInAPackage("..aml.detection..")
                .should().dependOnClassesThat().resideInAnyPackage("..aml.cases..", "..aml.str..")
                .allowEmptyShould(true)   // 아직 빈 기능 폴더는 통과, 클래스가 생기면 그대로 검사
                .check(classes);
        noClasses().that().resideInAPackage("..aml.cases..")
                .should().dependOnClassesThat().resideInAPackage("..aml.str..")
                .allowEmptyShould(true)   // 아직 빈 기능 폴더는 통과, 클래스가 생기면 그대로 검사
                .check(classes);
        noClasses().that().resideInAPackage("..aml.user..")
                .should().dependOnClassesThat().resideInAnyPackage("..aml.source..", "..aml.detection..", "..aml.cases..", "..aml.str..")
                .allowEmptyShould(true)   // 아직 빈 기능 폴더는 통과, 클래스가 생기면 그대로 검사
                .check(classes);
        noClasses().that().resideInAPackage("..aml.shared..")
                .should().dependOnClassesThat().resideInAnyPackage("..aml.source..", "..aml.detection..", "..aml.cases..", "..aml.str..", "..aml.user..")
                .allowEmptyShould(true)   // 아직 빈 기능 폴더는 통과, 클래스가 생기면 그대로 검사
                .check(classes);
    }

    @Test
    void 다른_기능의_infrastructure_직접_호출_금지() {
        for (String f : new String[]{"source", "detection", "cases", "str", "user"}) {
            noClasses().that().resideOutsideOfPackage("..aml." + f + "..")
                    .and().resideInAPackage("..aml..")
                    .should().dependOnClassesThat().resideInAPackage("..aml." + f + ".infrastructure..")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Test
    void 계층_의존_방향() {
        for (String f : new String[]{"source", "detection", "cases", "str", "user"}) {
            Architectures.layeredArchitecture().consideringOnlyDependenciesInLayers()
                    .withOptionalLayers(true)   // 아직 클래스가 없는 계층(빈 폴더)은 건너뛴다
                    .layer("api").definedBy("..aml." + f + ".api..")
                    .layer("application").definedBy("..aml." + f + ".application..")
                    .layer("domain").definedBy("..aml." + f + ".domain..")
                    .layer("infrastructure").definedBy("..aml." + f + ".infrastructure..")
                    .whereLayer("api").mayNotBeAccessedByAnyLayer()
                    .whereLayer("application").mayOnlyBeAccessedByLayers("api")
                    .whereLayer("domain").mayOnlyBeAccessedByLayers("api", "application", "infrastructure")
                    .whereLayer("infrastructure").mayNotBeAccessedByAnyLayer()
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Test
    void domain_은_Spring_을_모른다() {
        noClasses().that().resideInAPackage("..aml.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "org.springframework.stereotype..",
                        "org.springframework.transaction..", "org.springframework.security..")
                .allowEmptyShould(true)   // 아직 빈 기능 폴더는 통과, 클래스가 생기면 그대로 검사
                .check(classes);
    }

    @Test
    void 로그인_기록은_security_만_부른다() {   // SCRUM-19 — 팀원은 AccessLogWriter 의 열람 메서드만
        classes().that().haveFullyQualifiedName("com.team.aml.shared.audit.LoginAccessLogWriter")
                .should().onlyHaveDependentClassesThat()
                .resideInAnyPackage("..aml.shared.security..", "..aml.shared.audit..")
                .check(classes);
    }

    @Test
    void 열람_기록_엔티티와_저장소는_audit_안에서만_쓴다() {   // SCRUM-93 — 가짜 행 금지, 밖에서는 AccessLogWriter 만
        classes().that().haveFullyQualifiedName("com.team.aml.shared.audit.AccessLog")
                .or().haveFullyQualifiedName("com.team.aml.shared.audit.AccessLogRepository")
                .should().onlyHaveDependentClassesThat().resideInAPackage("..aml.shared.audit..")
                .check(classes);
    }

    @Test
    void api_는_Repository_에_의존하지_않는다() {   // SCRUM-94
        apiRepositoryRules().forEach(rule -> rule.check(classes));
    }

    @Test
    void Transactional_은_application_에만() {   // SCRUM-94
        transactionalRules().forEach(rule -> rule.check(classes));
    }

    @Test
    void Case_StrDraft_상태_변경은_자기_기능_안에서만() {   // SCRUM-94
        caseStrDraftRules().forEach(rule -> rule.check(classes));
    }

    @Test
    void api_는_다른_기능에_의존하지_않는다() {   // SCRUM-94
        apiOtherFeatureRules().forEach(rule -> rule.check(classes));
    }

    // 규칙이 실제로 잡는지는 ArchitectureRulesTest 가 가짜 클래스(archfixture)로 확인한다.

    static List<ArchRule> apiRepositoryRules() {
        return List.of(noClasses().that().resideInAPackage("..aml.*.api..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository").allowEmptyShould(true));
    }

    static List<ArchRule> transactionalRules() {
        var exceptions = List.of(
                // 공통 기록기: 호출자 트랜잭션에 참여하거나 분리하기 위한 예외
                "com.team.aml.shared.audit.AccessLogWriter", "com.team.aml.shared.audit.LoginAccessLogWriter",
                "com.team.aml.shared.ai.AiCallLogWriter",
                // 프레임워크가 호출하는 진입점
                "com.team.aml.user.infrastructure.DbUserDetailsService", "com.team.aml.user.infrastructure.DevUserSeeder");
        var forbidden = not(resideInAPackage("..aml.*.application.."))
                .and(describe("not an exact transactional exception", c -> !exceptions.contains(c.getName())));
        List<ArchRule> rules = new ArrayList<>();
        for (String annotation : List.of("org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional")) {
            rules.add(noClasses().that(forbidden).should().beAnnotatedWith(annotation).allowEmptyShould(true));
            rules.add(noMethods().that().areDeclaredInClassesThat(forbidden)
                    .should().beAnnotatedWith(annotation).allowEmptyShould(true));
        }
        return rules;
    }

    static List<ArchRule> caseStrDraftRules() {
        List<ArchRule> rules = new ArrayList<>();
        for (String feature : List.of("cases", "str")) {
            String owner = "com.team.aml." + feature + ".domain." + (feature.equals("cases") ? "Case" : "StrDraft");
            rules.add(noClasses().that().resideOutsideOfPackages("..aml." + feature + ".application..",
                            "..aml." + feature + ".domain..", "..aml." + feature + ".infrastructure..")
                    .should(accessTargetWhere(describe("access non-getter methods of " + owner, access -> {
                        String name = access.getTarget().getName();
                        return !(access instanceof JavaFieldAccess) && access.getTargetOwner().getName().equals(owner)
                                && !name.startsWith("get") && !name.startsWith("is")
                                && !List.of("<init>", "equals", "hashCode", "toString").contains(name);
                    }))).allowEmptyShould(true));
        }
        return rules;
    }

    static List<ArchRule> apiOtherFeatureRules() {
        return List.of("source", "detection", "cases", "str", "user").stream().<ArchRule>map(feature ->
                noClasses().that().resideInAPackage("..aml." + feature + ".api..")
                        .should().dependOnClassesThat().resideInAnyPackage(List.of("source", "detection", "cases", "str")
                                .stream().filter(target -> !target.equals(feature)).map(target -> "..aml." + target + "..")
                                .toArray(String[]::new)).allowEmptyShould(true)).toList();
    }
}
