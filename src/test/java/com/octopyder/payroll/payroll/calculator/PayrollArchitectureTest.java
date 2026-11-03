package com.octopyder.payroll.payroll.calculator;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class PayrollArchitectureTest {

    @Test
    @DisplayName("Calculator package production classes must only depend on JDK and its own package")
    void calculatorPackageOnlyDependsOnJdkAndSelf() {
        ArchRule rule = classes()
            .that().resideInAPackage("com.octopyder.payroll.payroll.calculator..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.octopyder.payroll.payroll.calculator..");

        rule.check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.octopyder.payroll.payroll.calculator")
        );
    }

    @Test
    @DisplayName("Calculator package must have no Spring or JPA/Jakarta persistence imports")
    void calculatorPackageMustNotDependOnSpringOrJpa() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.octopyder.payroll.payroll.calculator..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "jakarta.transaction..",
                "org.hibernate.."
            );

        rule.check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.octopyder.payroll.payroll.calculator")
        );
    }
}
