package com.anthropic.agentkit.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.anthropic.agentkit",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AgentModuleArchitectureTest {

    @ArchTest
    static final ArchRule diagnosisDoesNotDependOnCoding = noClasses()
            .that().resideInAPackage("..diagnosis..")
            .should().dependOnClassesThat().resideInAPackage("..coding..");
}
