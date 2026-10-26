package com.team.aml;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.team.aml");
    }

    @Test
    void 기능_간_의존_방향() {
        noClasses().that().resideInAPackage("..aml.source..")
                .should().dependOnClassesThat().resideInAnyPackage("..aml.detection..", "..aml.cases..", "..aml.str..")
                .check(classes);
        noClasses().that().resideInAPackage("..aml.detection..")
                .should().dependOnClassesThat().resideInAnyPackage("..aml.cases..", "..aml.str..")
                .check(classes);
        noClasses().that().resideInAPackage("..aml.cases..")
                .should().dependOnClassesThat().resideInAPackage("..aml.str..")
                .check(classes);
        noClasses().that().resideInAPackage("..aml.user..")
                .should().dependOnClassesThat().resideInAnyPackage("..aml.source..", "..aml.detection..", "..aml.cases..", "..aml.str..")
                .check(classes);
        noClasses().that().resideInAPackage("..aml.shared..")
                .should().dependOnClassesThat().resideInAnyPackage("..aml.source..", "..aml.detection..", "..aml.cases..", "..aml.str..", "..aml.user..")
                .check(classes);
    }

    @Test
    void 다른_기능의_infrastructure_직접_호출_금지() {
        for (String f : new String[]{"source", "detection", "cases", "str", "user"}) {
            noClasses().that().resideOutsideOfPackage("..aml." + f + "..")
                    .and().resideInAPackage("..aml..")
                    .should().dependOnClassesThat().resideInAPackage("..aml." + f + ".infrastructure..")
                    .check(classes);
        }
    }

    @Test
    void 계층_의존_방향() {
        for (String f : new String[]{"source", "detection", "cases", "str", "user"}) {
            Architectures.layeredArchitecture().consideringOnlyDependenciesInLayers()
                    .layer("api").definedBy("..aml." + f + ".api..")
                    .layer("application").definedBy("..aml." + f + ".application..")
                    .layer("domain").definedBy("..aml." + f + ".domain..")
                    .layer("infrastructure").definedBy("..aml." + f + ".infrastructure..")
                    .whereLayer("api").mayNotBeAccessedByAnyLayer()
                    .whereLayer("application").mayOnlyBeAccessedByLayers("api")
                    .whereLayer("domain").mayOnlyBeAccessedByLayers("api", "application", "infrastructure")
                    .whereLayer("infrastructure").mayNotBeAccessedByAnyLayer()
                    .check(classes);
        }
    }

    @Test
    void domain_은_Spring_을_모른다() {
        noClasses().that().resideInAPackage("..aml.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "org.springframework.stereotype..",
                        "org.springframework.transaction..", "org.springframework.security..")
                .check(classes);
    }
}
