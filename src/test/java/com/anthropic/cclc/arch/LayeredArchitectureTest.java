package com.anthropic.cclc.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "com.anthropic.cclc",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
class LayeredArchitectureTest {

    @ArchTest
    static final ArchRule domainHasNoLangchain4jDependency = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("dev.langchain4j..", "..langchain4j..");

    @ArchTest
    static final ArchRule domainHasNoInfrastructureDependency = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule domainHasNoApplicationDependency = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..application..");

    @ArchTest
    static final ArchRule domainHasNoInterfacesDependency = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..interfaces..");

    @ArchTest
    static final ArchRule applicationHasNoInfrastructureDependency = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule applicationHasNoInterfacesDependency = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..interfaces..");

    @ArchTest
    static final ArchRule noCyclesInLayers = slices()
            .matching("com.anthropic.cclc.(*)..")
            .should().beFreeOfCycles();
}
