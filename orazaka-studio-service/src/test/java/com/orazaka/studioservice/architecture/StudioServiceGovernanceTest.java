package com.orazaka.studioservice.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.test.architecture.ConfigBindingRules;
import com.orazaka.test.architecture.GovernanceRules;
import com.orazaka.test.architecture.LoggedContentRules;
import com.orazaka.test.architecture.PackPurityRules;
import com.orazaka.test.architecture.RunSurfaceRules;
import com.orazaka.test.architecture.SourceFileScanner;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Governance guardrails for orazaka-studio-service.
 *
 * <p>Beyond the shared rules, one Studio-specific fitness function: <b>no scripting engine may be
 * reachable</b>. A blueprint is untrusted input even from an admin, so an expression evaluator on
 * that path is a sandbox escape no review makes safe (ADR-034 §18).
 *
 * <p>The rule that no studio key may be a Java literal used to live here as well. It is now
 * [PACK-002] below — same keys, same reason (a hardcoded {@code "realestate-reels"} makes adding a
 * profession a deploy, which is the business model inverted), but repository-wide and covering the
 * Python worker this module's scan could never see. Two rules that could disagree about the same
 * property is worse than one that cannot.
 */
class StudioServiceGovernanceTest {

  /**
   * The rules below are repository-wide, not module-scoped: the worst pack coupling lives in the
   * Python media worker, which is in no Maven reactor, so a per-module scan could never see it.
   */
  private static final Path REPOSITORY_ROOT =
      PackPurityRules.locateRepositoryRoot(Path.of(System.getProperty("user.dir")));

  private static final String BASE_PACKAGE = "com.orazaka.studioservice";

  /** Engines that would turn a user-authored template into arbitrary code execution. */
  private static final List<String> SCRIPTING_ENGINES =
      List.of(
          "javax.script",
          "javax.el",
          "org.springframework.expression",
          "groovy.lang",
          "org.mvel2",
          "ognl");

  private static JavaClasses productionClasses;

  @BeforeAll
  static void importClasses() {
    productionClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);
  }

  @Test
  @DisplayName("[DOOR-001] no inbound HTTP entry dispatches a job")
  void noInboundEntryDispatchesAJob() {
    RunSurfaceRules.assertNoInboundEntryDispatchesAJob();
  }

  @Test
  @DisplayName("[SEAM-002] studio-service depends on no foreign Tier-3 implementation")
  void dependsOnNoForeignTier3() {
    GovernanceRules.assertNoForeignTier3Dependency(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[ERR-103] one top-level type per file")
  void oneTopLevelClassPerFile() {
    GovernanceRules.assertOneTopLevelClassPerFile(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[ERR-104] no redundant Orazaka prefix")
  void noRedundantPrefix() {
    GovernanceRules.assertNoRedundantPrefix(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[ERR-129] application/service holds only *Service types")
  void servicePackageHoldsOnlyServices() {
    GovernanceRules.assertServicePackageOnlyServices(
        productionClasses, BASE_PACKAGE + ".application.service");
  }

  @Test
  @DisplayName("[ERR-130] domain/model carries no transport DTOs")
  void domainHasNoTransportDtos() {
    GovernanceRules.assertDomainHasNoTransportDtos(productionClasses, BASE_PACKAGE + ".domain");
  }

  @Test
  @DisplayName("[AGENTS §4] no field injection")
  void noFieldInjection() {
    GovernanceRules.assertNoFieldInjection(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[AGENTS §4] no System.out / System.err")
  void noStandardStreams() {
    GovernanceRules.assertNoStandardStreams(productionClasses);
  }

  @Test
  @DisplayName("[ADR-034 §18] no scripting engine on the blueprint path")
  void noScriptingEngineReachable() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : productionSources()) {
      String body = strippedOfComments(file);
      for (String engine : SCRIPTING_ENGINES) {
        if (body.contains("import " + engine)) {
          violations.add(file.getFileName() + " imports " + engine);
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () ->
            "A blueprint is untrusted input even from an admin; the templating grammar is"
                + " deliberately not Turing-complete. Capability the grammar cannot express is a"
                + " new StepKind, never a new expression (ADR-034 §6.2/§18):\n  "
                + String.join("\n  ", violations));
  }

  private static List<Path> productionSources() throws IOException {
    try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
      return sources.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }

  /**
   * Strips comments before matching: the rules are about code. Prose that <i>names</i> a studio key
   * or a scripting engine to explain why it must not be used is the documentation we want, not a
   * violation.
   */
  private static String strippedOfComments(Path file) throws IOException {
    return Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
  }

  @Test
  @DisplayName("[ADR-035] no SecurityConfig opens /internal/** or /uploads/**")
  void internalAndMediaSurfacesStayAuthenticated() {
    GovernanceRules.assertNoPermitAllOnInternalOrUploads(
        Path.of(System.getProperty("user.dir"), "src", "main", "java"));
  }

  @Test
  @DisplayName("[ADR-035] /internal/v1 demands the SERVICE authority, not merely authentication")
  void internalSurfaceDemandsServiceAuthority() {
    GovernanceRules.assertInternalSurfaceRequiresServiceAuthority(
        Path.of(System.getProperty("user.dir"), "src", "main", "java"));
  }

  @Test
  @DisplayName("[AGENTS.md §4] requests run on virtual threads")
  void requestsRunOnVirtualThreads() {
    GovernanceRules.assertVirtualThreadsEnabled(Path.of(System.getProperty("user.dir")));
  }

  @Test
  @DisplayName("[PACK-002] no pack, studio or pack-capability key is a literal in engine code")
  void noPackKeyLiteralsInEngineCode() {
    GovernanceRules.assertNoPackKeyLiterals(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[PACK-003] engine code never branches on a pack identifier")
  void noPackKeyConditionalsInEngineCode() {
    GovernanceRules.assertNoPackKeyConditionals(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[EXEC-001] every in-process capability's handler_key has an executor")
  void everyCapabilityHasAnExecutor() {
    GovernanceRules.assertEveryCapabilityHasAnExecutor(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[EXEC-002] every capability's routing_key is drained by a declared worker")
  void everyCapabilityIsDrained() {
    GovernanceRules.assertEveryCapabilityIsDrained(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[ADR-055] no aggregate read of studio_run may see protected material")
  void protectedRunsAreExcludedFromEveryAggregate() throws java.io.IOException {
    // "Never exported to analytics, never used for training" is a claim, and a claim about data
    // needs something structural behind it. Today it is true partly by absence — there is no
    // training pipeline — and absence is the weakest possible guarantee: the first aggregate
    // somebody adds inherits nothing. So the rule is positive and about SQL: a statement that
    // counts, sums or groups over studio_run must say which class it is counting.
    java.util.List<String> offenders = new java.util.ArrayList<>();
    Path main = Path.of(System.getProperty("user.dir"), "src", "main", "java");
    if (!java.nio.file.Files.isDirectory(main)) {
      return;
    }
    try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(main)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = java.nio.file.Files.readString(file);
        for (String statement : source.split(";")) {
          String flat = statement.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
          boolean readsRuns = flat.contains("from studio_run");
          boolean aggregates =
              flat.contains("count(")
                  || flat.contains("sum(")
                  || flat.contains("avg(")
                  || flat.contains("group by");
          // An aggregate scoped to one actor is that actor reading about themselves, and it MUST
          // see their protected runs: a concurrency limit that ignored them would let anyone
          // exceed it by running a wellbeing pack. The rule is about the platform reading across
          // everyone, which is what an unscoped aggregate is.
          boolean scopedToOneActor = flat.contains("actor_id = ?");
          if (readsRuns && aggregates && !scopedToOneActor && !flat.contains("data_class")) {
            offenders.add(
                file.getFileName() + " aggregates studio_run without naming a data class");
          }
        }
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "[ADR-055] an aggregate over studio_run must filter on data_class — protected material is"
            + " excluded by a WHERE clause, not by everyone remembering:\n  "
            + String.join("\n  ", offenders));
  }

  @Test
  @DisplayName("[SAGA-001] one author closes a run's credit hold")
  void oneSettlementAuthor() {
    GovernanceRules.assertOneSettlementAuthor(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[SAGA-002] one author resolves what a dispatched step carries from its pack")
  void oneDeclarationAuthor() {
    GovernanceRules.assertOneDeclarationAuthor(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName("[SAGA-003] the saga's read-only invariants write nothing")
  void sagaReadersDoNotWrite() {
    GovernanceRules.assertSagaReadersDoNotWrite(REPOSITORY_ROOT);
  }

  @Test
  @DisplayName(
      "[CFG-001] every type the configuration binder builds has a constructor it can choose")
  void configurationBindsUnambiguously() {
    ConfigBindingRules.assertConfigurationBindsUnambiguously();
    ConfigBindingRules.assertInjectableComponentsHaveOneConstructor();
  }

  @Test
  @DisplayName("[ERR-113] No Environment injection in production beans")
  void noEnvironmentInjection() {
    SourceFileScanner.assertNoEnvironmentInjection(Path.of("src", "main", "java"));
  }

  /** [LOG-001] no logging call takes a prompt, a response body or a message text (ADR-064). */
  @Test
  void noLoggingCallTakesContent() {
    LoggedContentRules.assertNoLoggingCallTakesContent();
  }
}
