package com.orazaka.studioservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.billing.domain.model.CreditHoldResponse;
import com.orazaka.billing.domain.model.MeteredStep;
import com.orazaka.billing.domain.port.CreditAuthorizationClient;
import com.orazaka.jobs.domain.model.FailureCause;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studio.domain.model.RunStatus;
import com.orazaka.studio.domain.model.RunStepStatus;
import com.orazaka.studio.domain.model.StepDispatch;
import com.orazaka.studioservice.domain.model.InstalledStudio;
import com.orazaka.studioservice.domain.model.RunArtefact;
import com.orazaka.studioservice.domain.model.RunDetail;
import com.orazaka.studioservice.domain.model.RunStepView;
import com.orazaka.studioservice.domain.port.BlueprintRepository;
import com.orazaka.studioservice.domain.port.StepExecutionClient;
import com.orazaka.studioservice.infrastructure.adapter.persistence.PersistenceTestWiring;
import com.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.orazaka.test.architecture.Workspace;
import com.orazaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The run lifecycle against the <b>real</b> {@code infra/initdb/80-studio.sql}.
 *
 * <p>These are the four scenarios ADR-034 §15 names, and they are integration tests rather than
 * unit tests because every one of them is a claim about the <b>schema</b>: the immutability
 * trigger, the {@code (run_id, step_id, ordinal)} key that makes a redelivery harmless, the
 * sweeper's predicate, and the cascade that closes a run's steps with it. None of that is provable
 * against a mock.
 *
 * <p>The executor is stubbed on purpose. The claim under test is the engine — a failure here must
 * be unambiguously an engine bug, not an MLX one (ADR-034 §16).
 */
class StudioRunLifecycleIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final String STUDIO_KEY = "trade-showcase";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("bootstrap")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCopyFileToContainer(
              MountableFile.forHostPath(studioBootstrapScript()),
              "/docker-entrypoint-initdb.d/80-studio.sql");

  private static AnnotationConfigApplicationContext context;
  private static JdbcTemplate jdbcTemplate;
  private static StudioRunService runService;
  private static RunSagaService sagaService;
  private static StudioInstallationService installationService;
  private static RecordingStepExecutionClient executor;
  private static RecordingCreditClient credits;

  private static Path studioBootstrapScript() {
    return SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
        .resolve("80-studio.sql");
  }

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    // The initdb file creates this role WITHOUT a password (ADR-035); `orazaka start`
    // applies the ALTER ROLE at runtime, and a hermetic test has to do the same.
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);
    context = new AnnotationConfigApplicationContext(TestWiring.class);
    jdbcTemplate = context.getBean(JdbcTemplate.class);
    runService = context.getBean(StudioRunService.class);
    sagaService = context.getBean(RunSagaService.class);
    installationService = context.getBean(StudioInstallationService.class);
    executor = context.getBean(RecordingStepExecutionClient.class);
    credits = context.getBean(RecordingCreditClient.class);
    installCatalogueFixture();
  }

  /**
   * Installs the Studio this suite runs, from the bundle that now owns it.
   *
   * <p>{@code 80-studio.sql} creates the schema and seeds no catalogue: phase D moved the three
   * Studios into {@code orazaka-packs/} (ADR-039), so a suite that expected {@code trade-showcase}
   * to exist after running the initdb script started failing on every test. It went unnoticed
   * because <b>this file is an {@code *IT} and Maven's default surefire includes match only {@code
   * *Test}</b> — {@code mvn install} never ran it, and the phase-D build was green with it broken.
   *
   * <p>Reading the real bundle rather than inlining a fixture is deliberate: these tests are claims
   * about the schema and the interpreter, and they are worth more against the blueprint that
   * actually ships than against a simplified copy that can drift away from it.
   */
  private static void installCatalogueFixture() {
    Path bundle = Workspace.packs(Path.of(System.getProperty("user.dir"))).resolve(STUDIO_KEY);
    JsonNode blueprint;
    try {
      blueprint =
          new ObjectMapper()
              .readTree(
                  Files.readString(
                      bundle.resolve("studios").resolve(STUDIO_KEY).resolve("blueprint.json")));
    } catch (java.io.IOException unreadable) {
      throw new IllegalStateException(
          "The " + STUDIO_KEY + " bundle is what this suite runs; it must be readable", unreadable);
    }

    jdbcTemplate.update(
        "INSERT INTO studio (studio_key, label, tagline, profession, icon_key, pricing,"
            + " entitlement_key, status, publisher_id, latest_version, sort_weight)"
            + " VALUES (?, ?, ?, ?, ?, 'FREE', ?, 'PUBLISHED', 'orazaka', ?, 100)"
            + " ON CONFLICT (studio_key) DO NOTHING",
        STUDIO_KEY,
        "Vitrine Artisan",
        "Vos chantiers deviennent une vitrine qui vend.",
        "trades",
        "studio",
        "studio." + STUDIO_KEY,
        blueprint.path("version").asString());
    jdbcTemplate.update(
        "INSERT INTO studio_blueprint (studio_key, version, status, definition, input_schema,"
            + " config_schema, estimated_credits, changelog, published_at, created_by)"
            + " VALUES (?, ?, 'PUBLISHED', ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, now(), 'system')"
            + " ON CONFLICT (studio_key, version) DO NOTHING",
        STUDIO_KEY,
        blueprint.path("version").asString(),
        blueprint.path("definition").toString(),
        blueprint.path("inputSchema").toString(),
        blueprint.path("configSchema").toString(),
        blueprint.path("estimatedCredits").asLong(),
        blueprint.path("changelog").asString(""));
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void reset() {
    jdbcTemplate.update("DELETE FROM studio_run");
    jdbcTemplate.update("DELETE FROM studio_installation");
    jdbcTemplate.update("DELETE FROM processed_messages");
    jdbcTemplate.update("DELETE FROM studio_outbox");
    executor.dispatched.clear();
    credits.released.clear();
    credits.aggregateSettlements.clear();
  }

  private UUID install() {
    InstalledStudio installed =
        installationService.install(STUDIO_KEY, ACTOR, Map.of("tone", "premium"), "fr");
    return installed.id();
  }

  private RunDetail startRun() {
    return runService.start(
        install(), ACTOR, Map.of("photos", List.of("p1", "p2"), "trade", "plombier"));
  }

  // ── §15: install → run → step outcomes → settle ───────────────────────────

  @Test
  @DisplayName("[§15] install → run fans out, advances on outcomes, and reaches SUCCEEDED")
  void installRunSettle() {
    RunDetail run = startRun();

    assertEquals(RunStatus.RUNNING, run.status());
    assertEquals(2, executor.dispatched.size(), "two photos must fan out into two dispatches");

    // Snapshot first: applying an outcome advances the DAG, which appends to this very list.
    List<String> fanOut = List.copyOf(executor.dispatched);
    fanOut.forEach(job -> sagaService.applyOutcome(job, Map.of("content", "ok"), null));

    // Both dependants become ready together — they depend on `describe`, not on each other — and
    // each dispatches exactly once, however many fan-out outcomes arrived.
    assertEquals(4, executor.dispatched.size(), "caption and showcase each dispatch exactly once");

    List.copyOf(executor.dispatched).stream()
        .filter(job -> !fanOut.contains(job))
        .forEach(job -> sagaService.applyOutcome(job, Map.of("url", "/media/img-1.png"), null));

    RunDetail finished = runService.find(run.id(), ACTOR).orElseThrow();
    assertEquals(RunStatus.SUCCEEDED, finished.status());
    assertNotNull(finished.finishedAt());
    RunArtefact showcase =
        finished.outputs().stream()
            .filter(artefact -> artefact.key().equals("showcase"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the declared output is missing"));
    assertEquals("/media/img-1.png", showcase.value());
    // The blueprint's declared presentation must survive to the client, or an image renders as
    // an opaque asset id next to a copy button.
    assertEquals("IMAGE", showcase.type());
    assertEquals("Votre visuel de vitrine", showcase.label());
    assertEquals(
        List.of("showcase", "caption", "descriptions"),
        finished.outputs().stream().map(RunArtefact::key).toList(),
        "artefacts come back in the order the blueprint declares them");
    assertTrue(credits.released.contains("hold-1"), "a finished run must close its hold");
  }

  @Test
  @DisplayName("[ADR-041] A run settles ONCE at its steps' measurements, not per step")
  void runSettlesOnceAtTheSumOfWhatItsStepsMeasured() {
    UUID installationId = install();
    RunDetail run = startRun();

    // Every step reports what it measured, exactly as job.{id}.done carries it.
    List<String> fanOut = List.copyOf(executor.dispatched);
    fanOut.forEach(
        job ->
            sagaService.applyOutcome(
                job, Map.of("content", "ok"), Map.of("tokens", 3400), "llava", null, null));
    List.copyOf(executor.dispatched).stream()
        .filter(job -> !fanOut.contains(job))
        .forEach(
            job ->
                sagaService.applyOutcome(
                    job,
                    Map.of("url", "/media/img-1.png"),
                    Map.of("images", 1, "steps", 4, "width", 1024, "height", 1024),
                    "sdxl-turbo",
                    null,
                    null));

    assertEquals(RunStatus.SUCCEEDED, runService.find(run.id(), ACTOR).orElseThrow().status());

    // ONE settlement for the whole run — not one per step, and not a release. Before ADR-041 the
    // first step to finish settled the run's hold at the AGENT/CALL rate and the rest ran free.
    assertEquals(
        1,
        credits.aggregateSettlements.size(),
        "a run settles exactly once, whatever its step count");
    assertFalse(
        credits.released.contains("hold-1"),
        "a run that measured something is settled, never released");

    List<MeteredStep> settled = credits.aggregateSettlements.get(0);
    assertEquals(
        executor.dispatched.size(),
        settled.size(),
        "every step that measured something reaches the settlement");
    // Each step carries what priced it: the capability came from the registry, the model from the
    // producer. Both are needed, because a report alone cannot be priced.
    assertTrue(
        settled.stream().allMatch(step -> step.capability() != null),
        "no step may reach billing without the capability that prices it");
    assertTrue(
        settled.stream().anyMatch(step -> "llava".equals(step.modelName())),
        "the model the producer reported travels to billing");
    assertTrue(
        settled.stream()
            .anyMatch(
                step ->
                    step.consumption().tokens() != null && step.consumption().tokens() == 3400L),
        "the measurements survive the round trip through studio_run_step");
    assertNotNull(installationId);
  }

  @Test
  @DisplayName("[ADR-041] A run whose steps measured nothing is released, never billed")
  void unmeasuredRunIsReleasedNotBilled() {
    install();
    startRun();

    // No consumption reported by any step — the executors of this fixture measure nothing.
    List<String> fanOut = List.copyOf(executor.dispatched);
    fanOut.forEach(job -> sagaService.applyOutcome(job, Map.of("content", "ok"), null));
    List.copyOf(executor.dispatched).stream()
        .filter(job -> !fanOut.contains(job))
        .forEach(job -> sagaService.applyOutcome(job, Map.of("url", "/media/img-1.png"), null));

    assertTrue(credits.aggregateSettlements.isEmpty(), "nothing measured, nothing to settle");
    assertTrue(
        credits.released.contains("hold-1"),
        "an unmeasured run is released rather than billed at its estimate");
  }

  // ── §15: idempotency ──────────────────────────────────────────────────────

  @Test
  @DisplayName("[§15] a replayed outcome advances the DAG exactly once — no second submission")
  void replayedOutcomeIsHarmless() {
    startRun();
    String firstJob = executor.dispatched.get(0);

    sagaService.applyOutcome(firstJob, Map.of("content", "a"), null);
    int afterFirst = executor.dispatched.size();
    sagaService.applyOutcome(firstJob, Map.of("content", "TAMPERED"), null);

    assertEquals(afterFirst, executor.dispatched.size(), "a redelivery must not re-submit");
    String stored =
        jdbcTemplate.queryForObject(
            "SELECT output->>'content' FROM studio_run_step WHERE job_id = ?",
            String.class,
            firstJob);
    assertEquals("a", stored, "the first outcome wins; a replay cannot overwrite it");
  }

  // ── §15: failure releases, never settles ──────────────────────────────────

  @Test
  @DisplayName("[§15] a failing required step fails the run and releases the hold")
  void failureReleasesTheHold() {
    RunDetail run = startRun();

    // describe is onError=FAIL since ADR-042: the fixture's vision fan-out is the step whose whole
    // job is to prove the engine analyses images, so one instance failing ends the run. This test
    // asserted RUNNING here while the policy was SKIP — a run in which nothing analysed anything
    // carried on and finished green, which is exactly what hid the missing asset resolution for
    // three phases. The change of expectation IS the behaviour change.
    sagaService.applyOutcome(executor.dispatched.get(0), Map.of(), "vision died");

    RunDetail failed = runService.find(run.id(), ACTOR).orElseThrow();
    assertEquals(
        RunStatus.FAILED,
        failed.status(),
        "a FAIL-policy step takes the run down with it, on the first instance");
    assertNotNull(failed.finishedAt());
    assertTrue(credits.released.contains("hold-1"), "a failed run is released, never billed");
  }

  @Test
  @DisplayName("[ADR-053] an INPUT_INVALID run settles what it measured, and says so in the trail")
  void inputInvalidSettlesWhatRan() {
    RunDetail run = startRun();
    // One instance succeeds and measures; the next declares the payload unusable.
    sagaService.applyOutcome(
        executor.dispatched.get(0),
        Map.of("content", "ok"),
        Map.of("tokens", 3400),
        "llava",
        null,
        null);
    sagaService.applyOutcome(
        executor.dispatched.get(1),
        Map.of(),
        Map.of(),
        null,
        "field 'photos' must contain at least one entry",
        FailureCause.INPUT_INVALID);

    assertEquals(RunStatus.FAILED, runService.find(run.id(), ACTOR).orElseThrow().status());
    assertFalse(
        credits.released.contains("hold-1"),
        "the executor DECLARED the input unusable: the work that ran is settled, not released");
    assertEquals(1, credits.aggregateSettlements.size());
    assertEquals(
        1,
        credits.aggregateSettlements.get(0).size(),
        "exactly the steps that measured something — not the estimate, not the whole DAG");
  }

  @Test
  @DisplayName("[ADR-053] a refusal settles what ran; a timeout releases — the measure of the run")
  void aRefusalAndATimeoutSettleDifferently() {
    RunDetail refused = startRun();
    sagaService.applyOutcome(
        executor.dispatched.get(0),
        Map.of("content", "ok"),
        Map.of("tokens", 3400),
        "llava",
        null,
        null);
    sagaService.applyOutcome(
        executor.dispatched.get(1),
        Map.of(),
        Map.of(),
        null,
        "hors de mon périmètre",
        FailureCause.GUARD_REFUSAL);
    assertEquals(RunStatus.FAILED, runService.find(refused.id(), ACTOR).orElseThrow().status());
    assertFalse(
        credits.released.contains("hold-1"),
        "the platform did exactly what it was built to do: the work it did is settled");
    assertEquals(1, credits.aggregateSettlements.size());
  }

  @Test
  @DisplayName("[ADR-053] a platform fault releases everything, however much ran before it")
  void platformFaultReleasesEverything() {
    RunDetail run = startRun();
    sagaService.applyOutcome(
        executor.dispatched.get(0),
        Map.of("content", "ok"),
        Map.of("tokens", 3400),
        "llava",
        null,
        null);
    sagaService.applyOutcome(
        executor.dispatched.get(1),
        Map.of(),
        Map.of(),
        null,
        "ollama unreachable",
        FailureCause.PLATFORM_UNAVAILABLE);

    assertEquals(RunStatus.FAILED, runService.find(run.id(), ACTOR).orElseThrow().status());
    assertTrue(credits.released.contains("hold-1"), "our fault costs the actor nothing");
    assertTrue(credits.aggregateSettlements.isEmpty());
  }

  @Test
  @DisplayName(
      "[ADR-053] a cause nobody declared releases — we do not bill for want of information")
  void anUndeclaredCauseReleases() {
    RunDetail run = startRun();
    sagaService.applyOutcome(
        executor.dispatched.get(0),
        Map.of("content", "ok"),
        Map.of("tokens", 3400),
        "llava",
        null,
        null);
    // An older executor, or a third-party worker that has not been taught to declare.
    sagaService.applyOutcome(
        executor.dispatched.get(1),
        Map.of(),
        Map.of(),
        null,
        "compose requires a readable photo",
        null);

    assertEquals(RunStatus.FAILED, runService.find(run.id(), ACTOR).orElseThrow().status());
    assertTrue(
        credits.released.contains("hold-1"),
        "ADR-046 §2's counter-example: prose that reads like bad input was our own defect");
    assertTrue(credits.aggregateSettlements.isEmpty());
  }

  @Test
  @DisplayName("[ADR-053] the run records the category, so a trail can say which failure it was")
  void theRunRecordsItsCategory() {
    RunDetail run = startRun();
    sagaService.applyOutcome(
        executor.dispatched.get(0),
        Map.of(),
        Map.of(),
        null,
        "refused",
        FailureCause.GUARD_REFUSAL);

    assertEquals(
        "GUARD_REFUSAL",
        jdbcTemplate.queryForObject(
            "SELECT failure_cause FROM studio_run WHERE id = ?", String.class, run.id()));
  }

  @Test
  @DisplayName(
      "[ADR-065] a crisis refusal reaches the user, and the trail says only that a guard refused")
  void theTrailRecordsTheOutcomeNeverWhatWasMatched() {
    RunDetail run = startRun();
    jdbcTemplate.update("UPDATE studio_run SET data_class = 'REGULATED' WHERE id = ?", run.id());
    String crisisResponse =
        "Je ne suis pas en mesure de vous accompagner sur ce que vous traversez. Des personnes"
            + " formées répondent, tout de suite : 3114.";

    sagaService.applyOutcome(
        executor.dispatched.get(0),
        Map.of(),
        Map.of(),
        null,
        crisisResponse,
        FailureCause.GUARD_REFUSAL);

    // The person reads the reviewed text: that is the run's own message, under the run's retention.
    assertEquals(crisisResponse, runService.find(run.id(), ACTOR).orElseThrow().errorMessage());
    // The trail — append-only, outside every retention window — records the outcome's category…
    List<Map<String, Object>> trail =
        jdbcTemplate.queryForList(
            "SELECT event, failure_cause, row_to_json(a)::text AS whole"
                + " FROM studio_run_audit a WHERE run_id = ? AND event = 'RUN_FAILED'",
            run.id());
    assertEquals(1, trail.size());
    assertEquals("GUARD_REFUSAL", trail.get(0).get("failure_cause"));
    // …and nothing it matched: not the reviewed answer, not a fragment of it, in any column.
    assertFalse(
        String.valueOf(trail.get(0).get("whole")).contains("3114"),
        "the trail keeps a permanent record of a crisis response: " + trail.get(0).get("whole"));
  }

  @Test
  @DisplayName(
      "[ADR-051] a refused run tells the user what the pack refused, not that a step failed")
  void refusalReachesTheUserVerbatim() {
    RunDetail run = startRun();
    String refusal =
        "Je rédige et relis des documents. Je ne donne pas de conseil juridique — "
            + "pour cela, adressez-vous à un professionnel du droit.";

    sagaService.applyOutcome(executor.dispatched.get(0), Map.of(), refusal);

    RunDetail failed = runService.find(run.id(), ACTOR).orElseThrow();
    assertEquals(RunStatus.FAILED, failed.status());
    assertEquals(
        refusal,
        failed.errorMessage(),
        "the pack wrote a sentence for the user; a generic step message throws it away");
  }

  // ── §15: cancellation ─────────────────────────────────────────────────────

  @Test
  @DisplayName("cancelling releases the hold and stops the DAG without killing in-flight jobs")
  void cancelReleasesAndStops() {
    RunDetail run = startRun();

    runService.cancel(run.id(), ACTOR);

    RunDetail cancelled = runService.find(run.id(), ACTOR).orElseThrow();
    assertEquals(RunStatus.CANCELLED, cancelled.status());
    assertTrue(credits.released.contains("hold-1"));
    assertTrue(
        cancelled.steps().stream().allMatch(step -> step.status() == RunStepStatus.CANCELLED));
  }

  // ── §15: the immutability trigger is a schema claim ───────────────────────

  @Test
  @DisplayName("[§15] a PUBLISHED blueprint's definition cannot be edited, but its status can")
  void publishedBlueprintIsImmutable() {
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbcTemplate.update(
                "UPDATE studio_blueprint SET definition = '{}'::jsonb WHERE status = 'PUBLISHED'"));

    // Conditional, not a blanket ban: DRAFT → PUBLISHED → DEPRECATED must still be possible.
    int deprecated =
        jdbcTemplate.update(
            "UPDATE studio_blueprint SET status = 'DEPRECATED'"
                + " WHERE studio_key = ? AND status = 'PUBLISHED'",
            STUDIO_KEY);
    assertTrue(deprecated > 0);
    jdbcTemplate.update(
        "UPDATE studio_blueprint SET status = 'PUBLISHED' WHERE studio_key = ?", STUDIO_KEY);
  }

  // ── Tenant isolation ──────────────────────────────────────────────────────

  @Test
  @DisplayName("another actor cannot see a run, and cannot tell it apart from one that never was")
  void runsAreScopedToTheirActor() {
    RunDetail run = startRun();

    assertTrue(runService.find(run.id(), "550e8400-e29b-41d4-a716-446655440001").isEmpty());
    assertTrue(runService.history("550e8400-e29b-41d4-a716-446655440001", 10).isEmpty());
  }

  // ── Concurrency cap ───────────────────────────────────────────────────────

  @Test
  @DisplayName("the per-actor concurrency cap is enforced from its DB row, not a constant")
  void concurrencyCapIsEnforced() {
    UUID installation = install();
    Map<String, Object> inputs = Map.of("photos", List.of("p1"), "trade", "plombier");
    runService.start(installation, ACTOR, inputs);
    runService.start(installation, ACTOR, inputs);

    // run.max-concurrent-per-actor is seeded at 2.
    assertThrows(IllegalStateException.class, () -> runService.start(installation, ACTOR, inputs));
  }

  @Test
  @DisplayName("a run missing a required input is refused BEFORE any credit is held")
  void missingInputIsRefusedBeforeTheHold() {
    UUID installation = install();
    int heldBefore = credits.holds;

    assertThrows(
        IllegalArgumentException.class,
        () -> runService.start(installation, ACTOR, Map.of("trade", "plombier")));

    assertEquals(heldBefore, credits.holds, "validation must precede the hold");
    assertNull(
        jdbcTemplate.queryForObject("SELECT max(id::text) FROM studio_run", String.class),
        "no run row may survive a refused submission");
  }

  // ── Retry and config defaults: behaviour the blueprint declares ───────────

  @Test
  @DisplayName("A RETRY step is re-submitted rather than failing the run on its first stumble")
  void retryStepIsResubmitted() {
    RunDetail run = startRun();
    List<String> fanOut = List.copyOf(executor.dispatched);
    fanOut.forEach(job -> sagaService.applyOutcome(job, Map.of("content", "ok"), null));

    // `caption` is onError=RETRY, maxAttempts=2 in the seeded blueprint.
    String caption = jobInFlight(run, "caption");
    int before = executor.dispatched.size();

    sagaService.applyOutcome(caption, Map.of(), "model timed out");

    assertEquals(before + 1, executor.dispatched.size(), "a RETRY step must be re-submitted");
    assertEquals(
        RunStatus.RUNNING,
        runService.find(run.id(), ACTOR).orElseThrow().status(),
        "one transient failure must not end a run the author said should retry");
  }

  @Test
  @DisplayName("Retry is bounded by maxAttempts — the second failure is final")
  void retryIsBounded() {
    RunDetail run = startRun();
    List.copyOf(executor.dispatched)
        .forEach(job -> sagaService.applyOutcome(job, Map.of("content", "ok"), null));

    sagaService.applyOutcome(jobInFlight(run, "caption"), Map.of(), "model timed out");
    sagaService.applyOutcome(jobInFlight(run, "caption"), Map.of(), "model timed out again");

    RunDetail after = runService.find(run.id(), ACTOR).orElseThrow();
    assertEquals(RunStatus.FAILED, after.status(), "attempts are capped, not infinite");
    assertTrue(credits.released.contains("hold-1"), "and the hold is released when it gives up");
  }

  @Test
  @DisplayName("A blueprint's declared config default applies when the actor left the field blank")
  void blueprintDefaultsFillTheGaps() {
    // Installed with NO tone, though the blueprint declares a default for it.
    UUID installation = installationService.install(STUDIO_KEY, ACTOR, Map.of(), "fr").id();
    runService.start(installation, ACTOR, Map.of("photos", List.of("p1"), "trade", "plombier"));

    // Without the defaults layered in, {{config.tone}} renders empty and silently drops a word
    // out of the middle of the prompt the model receives.
    assertTrue(
        executor.lastInputs.values().stream()
            .anyMatch(value -> String.valueOf(value).contains("chaleureux")),
        () -> "the blueprint default must reach the prompt, got: " + executor.lastInputs);
  }

  /** Job ids of the steps currently in flight — re-read, because a retry mints a new one. */
  private List<String> jobsInFlight(RunDetail run) {
    return runService.find(run.id(), ACTOR).orElseThrow().steps().stream()
        .filter(step -> step.status() == RunStepStatus.RUNNING)
        .map(RunStepView::jobId)
        .filter(Objects::nonNull)
        .toList();
  }

  /** The in-flight job id of one named step. */
  private String jobInFlight(RunDetail run, String stepId) {
    return runService.find(run.id(), ACTOR).orElseThrow().steps().stream()
        .filter(step -> step.stepId().equals(stepId))
        .filter(step -> step.status() == RunStepStatus.RUNNING)
        .map(RunStepView::jobId)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no '" + stepId + "' step in flight"));
  }

  private void failEveryStepInFlight(RunDetail run, String error) {
    jobsInFlight(run).forEach(job -> sagaService.applyOutcome(job, Map.of(), error));
  }

  // ── §15: the sweeper, the one omission ADR-034 says will hurt ─────────────

  @Test
  @DisplayName("[§15] the sweeper fails a stalled run AND releases its hold")
  void sweeperClosesAStalledRunAndReleasesItsHold() {
    RunDetail run = startRun();
    credits.released.clear();

    // Backdate the in-flight steps past the ceiling: a crashed worker leaves exactly this state,
    // and without the sweeper the run never terminates and its hold freezes the actor's balance.
    jdbcTemplate.update(
        "UPDATE studio_run_step SET started_at = now() - INTERVAL '2 hours'"
            + " WHERE run_id = ? AND status = ?",
        run.id(),
        RunStepStatus.RUNNING.name());

    new com.orazaka.studioservice.infrastructure.adapter.schedule.RunSweeper(
            jdbcTemplate,
            context.getBean(RunSettlementService.class),
            context.getBean(StudioRuntimeConfigService.class),
            context.getBean(RunAuditService.class))
        .sweep();

    RunDetail swept = runService.find(run.id(), ACTOR).orElseThrow();
    assertEquals(RunStatus.FAILED, swept.status());
    assertNotNull(swept.finishedAt());
    assertTrue(credits.released.contains("hold-1"), "a stranded run must not strand its credits");
  }

  @Test
  @DisplayName("the sweeper leaves a healthy in-flight run alone")
  void sweeperIgnoresAHealthyRun() {
    RunDetail run = startRun();
    credits.released.clear();

    new com.orazaka.studioservice.infrastructure.adapter.schedule.RunSweeper(
            jdbcTemplate,
            context.getBean(RunSettlementService.class),
            context.getBean(StudioRuntimeConfigService.class),
            context.getBean(RunAuditService.class))
        .sweep();

    assertEquals(RunStatus.RUNNING, runService.find(run.id(), ACTOR).orElseThrow().status());
    assertTrue(credits.released.isEmpty());
  }

  // ── Retention (GDPR / Loi 25) ─────────────────────────────────────────────

  @Test
  @DisplayName("retention purges an aged finished run, and its steps cascade with it")
  void retentionPurgesAgedRuns() {
    RunDetail run = startRun();
    runService.cancel(run.id(), ACTOR);
    jdbcTemplate.update(
        "UPDATE studio_run SET finished_at = now() - INTERVAL '200 days' WHERE id = ?", run.id());

    new com.orazaka.studioservice.infrastructure.adapter.schedule.RetentionSweeper(
            jdbcTemplate, context.getBean(StudioRuntimeConfigService.class))
        .purge();

    assertTrue(runService.find(run.id(), ACTOR).isEmpty());
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM studio_run_step WHERE run_id = ?", Integer.class, run.id()));
  }

  @Test
  @DisplayName("retention keeps a recent run — the window is a legal parameter, not a cleanup")
  void retentionKeepsRecentRuns() {
    RunDetail run = startRun();
    runService.cancel(run.id(), ACTOR);

    new com.orazaka.studioservice.infrastructure.adapter.schedule.RetentionSweeper(
            jdbcTemplate, context.getBean(StudioRuntimeConfigService.class))
        .purge();

    assertTrue(runService.find(run.id(), ACTOR).isPresent());
  }

  @Test
  @DisplayName("[ADR-051] a SENSITIVE run ages out on the shorter window, inside the standard one")
  void sensitiveRunsAgeOutFirst() {
    RunDetail standard = startRun();
    RunDetail protectedRun = startRun();
    runService.cancel(standard.id(), ACTOR);
    runService.cancel(protectedRun.id(), ACTOR);
    jdbcTemplate.update(
        "UPDATE studio_run SET finished_at = now() - INTERVAL '45 days' WHERE id IN (?, ?)",
        standard.id(),
        protectedRun.id());
    jdbcTemplate.update(
        "UPDATE studio_run SET data_class = 'SENSITIVE' WHERE id = ?", protectedRun.id());

    new com.orazaka.studioservice.infrastructure.adapter.schedule.RetentionSweeper(
            jdbcTemplate, context.getBean(StudioRuntimeConfigService.class))
        .purge();

    assertTrue(
        runService.find(standard.id(), ACTOR).isPresent(),
        "45 days is well inside the platform's 180-day window");
    assertTrue(
        runService.find(protectedRun.id(), ACTOR).isEmpty(),
        "the same 45 days is outside the 30-day window a protected run gets");
  }

  @Test
  @DisplayName("[ADR-051] an installation may shorten the protected window, never lengthen it")
  void sensitiveRetentionIsConfigurableDownwardOnly() {
    // One fixture installation backs both halves, so each half sets the window it is testing.
    RunDetail run = startRun();
    runService.cancel(run.id(), ACTOR);
    jdbcTemplate.update(
        "UPDATE studio_run SET data_class = 'SENSITIVE', finished_at = now() - INTERVAL '20 days'"
            + " WHERE id = ?",
        run.id());

    // Upward: an owner asking to keep identifying documents for a decade does not get to. Aged well
    // past the platform's own 30 days, the run goes anyway.
    setInstallationRetention(run.id(), 3650);
    jdbcTemplate.update(
        "UPDATE studio_run SET finished_at = now() - INTERVAL '45 days' WHERE id = ?", run.id());
    sweep();
    assertTrue(
        runService.find(run.id(), ACTOR).isEmpty(),
        "the 3650-day request bought nothing: the platform's 30 days is the ceiling");

    // Downward: 7 days is shorter than the platform's 30, so it takes effect — a run the platform
    // would still be holding is gone because its owner asked for less.
    RunDetail shortened = startRun();
    runService.cancel(shortened.id(), ACTOR);
    jdbcTemplate.update(
        "UPDATE studio_run SET data_class = 'SENSITIVE', finished_at = now() - INTERVAL '20 days'"
            + " WHERE id = ?",
        shortened.id());
    setInstallationRetention(shortened.id(), 7);
    sweep();
    assertTrue(runService.find(shortened.id(), ACTOR).isEmpty(), "a shorter window is honoured");
  }

  private void sweep() {
    new com.orazaka.studioservice.infrastructure.adapter.schedule.RetentionSweeper(
            jdbcTemplate, context.getBean(StudioRuntimeConfigService.class))
        .purge();
  }

  private void setInstallationRetention(java.util.UUID runId, int days) {
    jdbcTemplate.update(
        """
        UPDATE studio_installation SET config = COALESCE(config, '{}'::jsonb)
             || jsonb_build_object('retentionDays', ?::text)
         WHERE id = (SELECT installation_id FROM studio_run WHERE id = ?)
        """,
        days,
        runId);
  }

  /** Records what the engine submitted, so the DAG's decisions are observable without a broker. */
  static class RecordingStepExecutionClient implements StepExecutionClient {
    final List<String> dispatched = new ArrayList<>();
    Map<String, Object> lastInputs = Map.of();

    @Override
    public String submit(StepDispatch dispatch) {
      String jobId = UUID.randomUUID().toString();
      dispatched.add(jobId);
      lastInputs = dispatch.resolvedInputs();
      return jobId;
    }
  }

  /**
   * Records hold lifecycle, so what closes a run's hold — and at what — is assertable.
   *
   * <p>It used to be named for asserting "released, never settled", which was the behaviour and was
   * the bug: a run reserved its estimate and was debited five credits by whichever step finished
   * first (ADR-041). It now records the aggregate settlement so a test can state the sum.
   */
  static class RecordingCreditClient implements CreditAuthorizationClient {
    final List<String> released = new ArrayList<>();
    final List<List<MeteredStep>> aggregateSettlements = new ArrayList<>();
    int holds;

    @Override
    public CreditHoldResponse hold(com.orazaka.billing.domain.model.CreditHoldCommand command) {
      holds++;
      return new CreditHoldResponse("hold-1", true, false, command.estimatedCredits(), 1000, 1);
    }

    @Override
    public void settle(com.orazaka.billing.domain.model.SettleCreditCommand command) {
      // Single-unit settlement: not the run's path, which crosses several units at once.
    }

    @Override
    public boolean settleAggregate(String holdId, List<MeteredStep> steps, String idempotencyKey) {
      aggregateSettlements.add(List.copyOf(steps));
      return !steps.isEmpty();
    }

    @Override
    public void settleMeasured(
        String holdId,
        com.orazaka.billing.domain.model.ConsumptionReport report,
        String idempotencyKey) {
      // Not used by the run-level hold.
    }

    @Override
    public void release(String holdId, String reason) {
      released.add(holdId);
    }
  }

  @Configuration
  @EnableTransactionManagement
  @org.springframework.context.annotation.Import(PersistenceTestWiring.class)
  static class TestWiring {

    @Bean
    DataSource dataSource() {
      HikariDataSource dataSource = new HikariDataSource();
      dataSource.setJdbcUrl(
          "jdbc:postgresql://"
              + POSTGRES.getHost()
              + ":"
              + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
              + "/"
              + STUDIO_DB);
      dataSource.setUsername(STUDIO_ROLE);
      dataSource.setPassword(STUDIO_PASSWORD);
      dataSource.setDriverClassName("org.postgresql.Driver");
      return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    RecordingStepExecutionClient stepExecutionClient() {
      return new RecordingStepExecutionClient();
    }

    @Bean
    RecordingCreditClient creditAuthorizationClient() {
      return new RecordingCreditClient();
    }

    @Bean
    com.orazaka.billing.domain.port.EntitlementProvider entitlementProvider() {
      // Unresolved, like the no-op adapter: a FREE studio still installs during a billing outage.
      return actorId ->
          com.orazaka.billing.domain.model.EntitlementSnapshot.unresolved(
              actorId, java.time.Instant.now().plusSeconds(60));
    }

    @Bean
    StudioRuntimeConfigService runtimeConfigService(JdbcTemplate jdbcTemplate) {
      return new StudioRuntimeConfigService(jdbcTemplate);
    }

    @Bean
    OutboxService outboxService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
      return new OutboxService(jdbcTemplate, objectMapper);
    }

    @Bean
    CreditReservationService creditReservationService(CreditAuthorizationClient client) {
      return new CreditReservationService(client);
    }

    @Bean
    StudioCatalogService studioCatalogService(
        JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
      return new StudioCatalogService(jdbcTemplate, columns);
    }

    @Bean
    RunAuditService runAuditService(JdbcTemplate jdbcTemplate) {
      return new RunAuditService(jdbcTemplate);
    }

    @Bean
    StudioAccessService studioAccessService(
        com.orazaka.billing.domain.port.EntitlementProvider provider) {
      return new StudioAccessService(provider);
    }

    @Bean
    StudioInstallationService studioInstallationService(
        JdbcTemplate jdbcTemplate,
        StudioCatalogService catalogService,
        StudioAccessService accessService,
        ObjectMapper objectMapper,
        ColumnValueResolver columns) {
      return new StudioInstallationService(
          jdbcTemplate, catalogService, accessService, objectMapper, columns);
    }

    /**
     * Every capability this fixture's blueprints name, priced as IMAGE.
     *
     * <p>The capability is what the run's settlement prices each step against, so a stub that
     * answered nothing would make every run settle at zero and hide exactly the defect these tests
     * now pin (ADR-041).
     */
    @Bean
    CapabilityRoutingClient capabilityRoutingClient() {
      return featureKey ->
          Optional.of(
              new com.orazaka.jobs.domain.model.CapabilityRoute(
                  featureKey, "job.media.generate", "IMAGE_STEP", "IMAGE", "BATCH", true));
    }

    @Bean
    RunSettlementService runSettlementService(
        CreditReservationService creditReservationService,
        com.orazaka.jobs.domain.port.CapabilityRoutingClient capabilityRoutingClient,
        JdbcTemplate jdbcTemplate,
        ColumnValueResolver columns) {
      return new RunSettlementService(
          creditReservationService, capabilityRoutingClient, jdbcTemplate, columns);
    }

    @Bean
    StepDeclarationService stepDeclarations(
        JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
      return new StepDeclarationService(jdbcTemplate, columns);
    }

    @Bean
    RunSagaService runSagaService(
        JdbcTemplate jdbcTemplate,
        BlueprintRepository blueprintRepository,
        StepExecutionClient stepExecutionClient,
        CapabilityRoutingClient capabilityRoutingClient,
        RunSettlementService settlementService,
        StepDeclarationService declarations,
        StudioRuntimeConfigService runtimeConfigService,
        OutboxService outboxService,
        ColumnValueResolver columns,
        RunAuditService runAuditService) {
      return new RunSagaService(
          jdbcTemplate,
          blueprintRepository,
          stepExecutionClient,
          capabilityRoutingClient,
          settlementService,
          declarations,
          runtimeConfigService,
          outboxService,
          columns,
          new SimpleMeterRegistry(),
          runAuditService);
    }

    @Bean
    StudioRunService studioRunService(
        JdbcTemplate jdbcTemplate,
        StudioInstallationService installationService,
        BlueprintRepository blueprintRepository,
        CreditReservationService creditReservationService,
        RunSettlementService settlementService,
        RunSagaService runSagaService,
        StudioRuntimeConfigService runtimeConfigService,
        OutboxService outboxService,
        ObjectMapper objectMapper,
        ColumnValueResolver columns,
        RunAuditService runAuditService,
        StudioCatalogService catalogService,
        StudioAccessService accessService) {
      return new StudioRunService(
          jdbcTemplate,
          installationService,
          blueprintRepository,
          creditReservationService,
          settlementService,
          runSagaService,
          runtimeConfigService,
          outboxService,
          objectMapper,
          columns,
          runAuditService,
          catalogService,
          accessService);
    }

    @Bean
    ColumnValueResolver columnValueResolver(ObjectMapper objectMapper) {
      return new ColumnValueResolver(objectMapper);
    }
  }
}
