package com.commercesettle.financemock;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.commercesettle.financemock",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  // Empty layers are expected in the bootstrap; these rules guard subsequent business code.
  @ArchTest
  static final ArchRule DOMAIN_IS_INDEPENDENT =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..presentation..",
              "..application..",
              "..infrastructure..",
              "org.springframework..",
              "org.apache.kafka..",
              "io.lettuce..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule APPLICATION_DOES_NOT_USE_ADAPTERS_OR_HTTP_LAYER =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..presentation..", "..infrastructure..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule PRESENTATION_DOES_NOT_USE_ADAPTERS =
      noClasses()
          .that()
          .resideInAPackage("..presentation..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule ADAPTERS_DO_NOT_USE_HTTP_LAYER =
      noClasses()
          .that()
          .resideInAPackage("..infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..presentation..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule SERVICES_DO_NOT_IMPORT_EACH_OTHER =
      noClasses()
          .that()
          .resideInAPackage("com.commercesettle.financemock..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.commercesettle.seller..",
              "com.commercesettle.payment..",
              "com.commercesettle.settlement..")
          .allowEmptyShould(true);
}
