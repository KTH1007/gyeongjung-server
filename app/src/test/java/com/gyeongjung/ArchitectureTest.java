package com.gyeongjung;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

@AnalyzeClasses(packages = "com.gyeongjung", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final int MODULE_SEGMENT = 2;

    @ArchTest
    static void domainDoesNotDependOnSpringOrInfrastructure(JavaClasses classes) {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @ArchTest
    static void applicationDoesNotDependOnInfrastructure(JavaClasses classes) {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @ArchTest
    static void entitiesAndRepositoriesStayInsideTheirModule(JavaClasses classes) {
        classes()
                .that()
                .areAnnotatedWith("jakarta.persistence.Entity")
                .or()
                .areAssignableTo("org.springframework.data.repository.Repository")
                .should(new OnlyUsedFromOwnModule())
                .allowEmptyShould(true)
                .check(classes);
    }

    private static String moduleOf(JavaClass javaClass) {
        String[] segments = javaClass.getPackageName().split("\\.");
        return segments.length > MODULE_SEGMENT ? segments[MODULE_SEGMENT] : "";
    }

    private static final class OnlyUsedFromOwnModule extends ArchCondition<JavaClass> {

        OnlyUsedFromOwnModule() {
            super("only be used from its own module");
        }

        @Override
        public void check(JavaClass target, ConditionEvents events) {
            String module = moduleOf(target);
            target.getDirectDependenciesToSelf().stream()
                    .filter(dependency -> !moduleOf(dependency.getOriginClass()).equals(module))
                    .forEach(dependency ->
                            events.add(
                                    SimpleConditionEvent.violated(
                                            dependency, dependency.getDescription())));
        }
    }
}
