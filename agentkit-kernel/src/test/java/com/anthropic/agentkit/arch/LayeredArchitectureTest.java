package com.anthropic.agentkit.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "com.anthropic.agentkit",
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
    static final ArchRule kernelHasNoDiagnosisDependency = noClasses()
            .that().resideOutsideOfPackage("..diagnosis..")
            .and().resideOutsideOfPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..diagnosis..");

    @ArchTest
    static final ArchRule cliIsNotReferencedOutsideCliShell = noClasses()
            .that().resideOutsideOfPackage("..interfaces.cli..")
            .should().dependOnClassesThat().resideInAPackage("..interfaces.cli..");

    @ArchTest
    static final ArchRule contextCompactionBelongsToKernelApplication = classes()
            .that().haveSimpleName("ContextCompactionService")
            .should().resideInAPackage("..application.context..");

    @ArchTest
    static final ArchRule subAgentToolBelongsToKernelTools = classes()
            .that().haveSimpleName("SubAgentTool")
            .should().resideInAPackage("..infrastructure.tools..");

    @ArchTest
    static final ArchRule streamJsonBelongsToKernelInfrastructure = classes()
            .that().haveSimpleName("ClaudeStreamJsonListener")
            .or().haveSimpleName("ClaudeStreamJsonWriter")
            .should().resideInAPackage("..infrastructure.streamjson..");

    @ArchTest
    static final ArchRule noCyclesInLayers = slices()
            .matching("com.anthropic.agentkit.(*)..")
            .should().beFreeOfCycles();
}
