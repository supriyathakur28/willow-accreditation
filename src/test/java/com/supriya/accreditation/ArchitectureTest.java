package com.supriya.accreditation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Module boundaries from the design document, enforced on every build. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.supriya.accreditation");

    @Test
    void domainIsPureJava() {
        classes().that().resideInAPackage("..domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("..domain..", "java..")
                .because("the business rules are pure functions with no framework, database or messaging code")
                .check(CLASSES);
    }

    @Test
    void writeSideDoesNotReachIntoTheReadSide() {
        noClasses().that().resideInAPackage("com.supriya.accreditation.command..")
                .should().dependOnClassesThat().resideInAPackage("com.supriya.accreditation.query..")
                .because("the admin write path never reads the projection; it decides on the locked row only")
                .check(CLASSES);
    }

    @Test
    void readSideDoesNotReachIntoTheWriteSide() {
        noClasses().that().resideInAPackage("com.supriya.accreditation.query..")
                .should().dependOnClassesThat().resideInAPackage("com.supriya.accreditation.command..")
                .because("the read model is built from events alone, so it can move to its own service")
                .check(CLASSES);
    }

    @Test
    void platformKnowsNoBusinessModule() {
        noClasses().that().resideInAPackage("com.supriya.accreditation.platform..")
                .should().dependOnClassesThat().resideInAnyPackage("com.supriya.accreditation.domain..",
                        "com.supriya.accreditation.command..", "com.supriya.accreditation.query..",
                        "com.supriya.accreditation.users..", "com.supriya.accreditation.events..")
                .because("platform code (errors, request IDs, transactions, idempotency) is shared plumbing")
                .check(CLASSES);
    }
}
