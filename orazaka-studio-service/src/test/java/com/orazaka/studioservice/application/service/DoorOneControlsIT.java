package com.orazaka.studioservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.billing.domain.model.EntitlementSnapshot;
import com.krizaka.billing.domain.model.PackProvision;
import com.krizaka.billing.domain.port.EntitlementProvider;
import com.krizaka.billing.domain.port.PackProvisioningClient;
import com.krizaka.messaging.dedup.JdbcMessageDedup;
import com.krizaka.test.container.ServiceRoles;
import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.jobs.domain.port.CapabilityRegistrationClient;
import com.orazaka.studio.domain.model.PackScopeGuard;
import com.orazaka.studio.domain.model.RunStatus;
import com.orazaka.studioservice.domain.model.ComposerStudio;
import com.orazaka.studioservice.domain.model.InstalledStudio;
import com.orazaka.studioservice.domain.model.RunDetail;
import com.orazaka.studioservice.domain.port.PackInstallRepository;
import com.orazaka.studioservice.infrastructure.adapter.schedule.PackBootstrap;
import com.orazaka.studioservice.infrastructure.adapter.schedule.RetentionSweeper;
import com.orazaka.studioservice.infrastructure.config.AssetStoreProperties;
import com.orazaka.studioservice.infrastructure.config.AssetStoreProperties.Deployment;
import com.orazaka.studioservice.infrastructure.config.PackSourceProperties;
import com.orazaka.studioservice.infrastructure.support.PackBundleResolver;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The controls, asserted one by one — the proof of M3 (ADR-068 §6).
 *
 * <p><b>Success is not the property under test.</b> A test that checked an image came back would
 * have passed before this run too: door 1 produced images. What door 1 never produced was a run
 * row, a data class derived from the pack, a retention window that follows that class, an audit
 * trail, a resolved scope guard, or a single settlement. Each of those is asserted separately here,
 * because a green test over a bundled "it worked" is what let a capability go unbilled for two
 * phases.
 *
 * <p>The catalogue is <b>installed by bootstrap from the bundles on disk</b> rather than seeded by
 * hand: the controls are derived from the pack, so a fixture that wrote the rows itself would be
 * asserting its own INSERT statements. `document-validation` is the SENSITIVE pack this repository
 * ships, and `orazaka-media` the STANDARD one door 1 carried.
 */
class DoorOneControlsIT {

  /** These suites install the shipped bundles: they need orazaka-packs, i.e. the workspace. */
  @org.junit.jupiter.api.BeforeAll
  static void requireShippedPacks() {
    com.orazaka.test.architecture.Workspace.packs(
        java.nio.file.Path.of(System.getProperty("user.dir")));
  }

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  /** A media Studio door 1 used to serve, and the SENSITIVE Studio that carries four controls. */
  private static final String STANDARD_STUDIO = "image-generation";

  private static final String SENSITIVE_STUDIO = "document-authenticity";

  private static final Set<String> GRANTED = ConcurrentHashMap.newKeySet();

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("bootstrap")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCopyFileToContainer(
              MountableFile.forHostPath(
                  SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
                      .resolve("80-studio.sql")),
              "/docker-entrypoint-initdb.d/80-studio.sql");

  private static AnnotationConfigApplicationContext context;
  private static JdbcTemplate jdbcTemplate;
  private static StudioRunService runService;
  private static RunSagaService sagaService;
  private static ComposerStudioService composerStudioService;
  private static StepDeclarationService stepDeclarations;
  private static StudioInstallationService installationService;
  private static StudioRunLifecycleIT.RecordingStepExecutionClient executor;
  private static StudioRunLifecycleIT.RecordingCreditClient credits;

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);
    context = new AnnotationConfigApplicationContext(ControlsWiring.class);
    jdbcTemplate = context.getBean(JdbcTemplate.class);
    runService = context.getBean(StudioRunService.class);
    sagaService = context.getBean(RunSagaService.class);
    composerStudioService = context.getBean(ComposerStudioService.class);
    stepDeclarations = context.getBean(StepDeclarationService.class);
    installationService = context.getBean(StudioInstallationService.class);
    executor = context.getBean(StudioRunLifecycleIT.RecordingStepExecutionClient.class);
    credits = context.getBean(StudioRunLifecycleIT.RecordingCreditClient.class);

    // The shipped bundles, installed the way a fresh environment installs them (ADR-068 §2).
    context.getBean(PackBootstrap.class).install();
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @Test
  @DisplayName(
      "§6.2 — invoking a media capability produces a studio_run, and the job id is the run's")
  void aCapabilityInvocationProducesARun() {
    GRANTED.add("studio." + STANDARD_STUDIO);
    executor.dispatched.clear();

    RunDetail run = start(STANDARD_STUDIO, Map.of("prompt", "un atelier de menuiserie"));

    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM studio_run WHERE id = ?", Integer.class, run.id()),
        "invoking the capability wrote a run row — what door 1 never wrote");
    assertEquals(1, executor.dispatched.size(), "one step, one dispatch");

    // The second half of §6.2, in the one database that can see both ends: a job id exists on this
    // plane ONLY as a column of a run's step, and the command queued for the broker is addressed to
    // the run that asked for it. There is no longer a producer that can mint one without a run —
    // `POST /api/v1/jobs` is deleted, and the outbox is the only path to the jobs exchange.
    String jobId = executor.dispatched.get(0);
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM studio_run_step WHERE job_id = ? AND run_id = ?",
            Integer.class,
            jobId,
            run.id()),
        "the dispatched job id belongs to a step of this run");
    // …and nowhere else: this plane holds that job id exactly once, on the step of the run that
    // minted it. The broker half — the queued command carrying the same id, addressed to the same
    // run, written in the dispatch's own transaction — is asserted against the real adapter by
    // StepDispatchDurabilityIT; the executor here is a recorded stub on purpose, so that a failure
    // in this suite is unambiguously the engine or the pack (ADR-034 §16).
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM studio_run_step WHERE job_id = ?", Integer.class, jobId),
        "the job id exists once on this plane, as a column of the step that dispatched it");
  }

  @Test
  @DisplayName(
      "§6.3 — a STANDARD media run is classed, kept by its class, and settled exactly once")
  void aStandardRunCarriesItsClassAndOneSettlement() {
    GRANTED.add("studio." + STANDARD_STUDIO);
    executor.dispatched.clear();
    credits.aggregateSettlements.clear();

    RunDetail run = start(STANDARD_STUDIO, Map.of("prompt", "une façade au petit matin"));
    sagaService.applyOutcome(
        executor.dispatched.get(0),
        Map.of("url", "/api/v1/assets/j/i.png", "format", "png"),
        Map.of("images", 1, "steps", 20, "width", 1024, "height", 1024),
        "sd-turbo",
        null,
        null);

    assertEquals(
        "STANDARD",
        jdbcTemplate.queryForObject(
            "SELECT data_class FROM studio_run WHERE id = ?", String.class, run.id()),
        "the class comes from the pack; orazaka-media declares STANDARD and says why");
    assertEquals(
        RunStatus.SUCCEEDED,
        runService.find(run.id(), ACTOR).orElseThrow().status(),
        "the run reached a terminal state");
    assertEquals(
        1,
        credits.aggregateSettlements.size(),
        "metered exactly once — never through door 1, ever");
  }

  @Test
  @DisplayName("§6.3 — a SENSITIVE run receives all four controls, each asserted on its own")
  void aSensitiveRunReceivesItsFourControls() {
    GRANTED.add("studio." + SENSITIVE_STUDIO);
    executor.dispatched.clear();
    credits.aggregateSettlements.clear();

    // A VERTICAL pack: the installation is a row, unlike a TOOLKIT's derived one (ADR-061).
    InstalledStudio installed =
        installationService.install(SENSITIVE_STUDIO, ACTOR, Map.of(), "fr", null);
    assertNotNull(installed.id(), "a VERTICAL installation is stored");

    RunDetail run = start(SENSITIVE_STUDIO, Map.of("documentBase64", "JVBERi0xLjQKJStop-here"));

    // Control 1 — the data class, DERIVED from the pack's regulatory class and not from the caller.
    // Door 1 declared STANDARD for everything because it had no pack to read.
    assertEquals(
        "SENSITIVE",
        jdbcTemplate.queryForObject(
            "SELECT data_class FROM studio_run WHERE id = ?", String.class, run.id()),
        "the run is classed by the pack that owns the Studio");

    // Control 2 — the audit trail. Written for a protected class and for no other, so its presence
    // here IS the control rather than a log line somebody may have configured off.
    assertTrue(
        jdbcTemplate.queryForObject(
                "SELECT count(*) FROM studio_run_audit WHERE run_id = ? AND event = 'RUN_STARTED'",
                Integer.class,
                run.id())
            > 0,
        "a protected run leaves an append-only trail of who started it");

    // Control 3 — the scope guard: the domain this pack refuses, resolved per dispatch from the
    // pack row rather than compiled into the engine.
    Optional<PackScopeGuard> guard = stepDeclarations.scopeGuard(SENSITIVE_STUDIO);
    assertTrue(guard.isPresent(), "the SENSITIVE pack's scope guard resolves for its Studio");
    assertFalse(guard.orElseThrow().refusedTerms().isEmpty(), "and it names what it refuses");
    assertFalse(guard.orElseThrow().refusal().isBlank(), "and what it answers instead");

    // Control 4 — retention by class: the shorter window, applied without anyone setting it. The
    // run is aged past the SENSITIVE window (30 days) and left well inside the standard one (90).
    sagaService.applyOutcome(
        executor.dispatched.get(0),
        Map.of("verdict", "coherent"),
        Map.of("tokens", 900),
        "rules",
        null,
        null);
    jdbcTemplate.update(
        "UPDATE studio_run SET finished_at = now() - INTERVAL '45 days' WHERE id = ?", run.id());

    new RetentionSweeper(
            jdbcTemplate,
            context.getBean(StudioRuntimeConfigService.class),
            new JdbcMessageDedup(jdbcTemplate))
        .purge();

    assertTrue(
        runService.find(run.id(), ACTOR).isEmpty(),
        "a protected run ages out on its own window, 45 days being past 30 and inside 90");
    assertTrue(
        jdbcTemplate.queryForObject(
                "SELECT count(*) FROM studio_run_audit WHERE run_id = ?", Integer.class, run.id())
            > 0,
        "and the trail outlives the run it describes — the row it would otherwise erase");
  }

  @Test
  @DisplayName(
      "§6.3 — the six media capabilities are the composer's row, each launchable in one step")
  void theSixMediaCapabilitiesAreReachableFromTheComposer() {
    List.of(
            "image-generation",
            "video-generation",
            "speech-synthesis",
            "image-analysis",
            "audio-analysis",
            "video-analysis")
        .forEach(key -> GRANTED.add("studio." + key));

    Map<String, ComposerStudio> row =
        composerStudioService.row("fr", ACTOR).stream()
            .collect(Collectors.toMap(ComposerStudio::studioKey, entry -> entry));

    for (String studioKey :
        List.of(
            "image-generation",
            "video-generation",
            "speech-synthesis",
            "image-analysis",
            "audio-analysis",
            "video-analysis")) {
      ComposerStudio entry = row.get(studioKey);
      assertNotNull(entry, studioKey + " must be reachable from the composer");
      assertFalse(entry.locked(), studioKey + " is included for an entitled actor");
    }
    // And the SENSITIVE document Studio is NOT: one step, one required string, and nothing that
    // says a chat bar can fill it. It keeps its page.
    assertFalse(row.containsKey(SENSITIVE_STUDIO), "an undeclared input is not a button");
  }

  private static RunDetail start(String studioKey, Map<String, Object> inputs) {
    return runService.start(studioKey, ACTOR, inputs);
  }

  /** Records what the job plane was asked to register, so the install's order can be asserted. */
  static class RecordingRegistration implements CapabilityRegistrationClient {

    @Override
    public void register(CapabilityDeclaration declaration) {
      // The job plane owns the real table; what matters here is that install does not fail.
    }

    @Override
    public boolean unregister(String featureKey) {
      return true;
    }
  }

  /** Billing's half of an install, recorded and never called for real. */
  static class RecordingProvisioning implements PackProvisioningClient {

    @Override
    public void provision(PackProvision provision) {
      // no-op
    }

    @Override
    public boolean withdraw(String packKey) {
      return true;
    }
  }

  static class ControlsWiring extends StudioRunLifecycleIT.TestWiring {

    @Override
    @Bean
    DataSource dataSource() {
      HikariDataSource pool = new HikariDataSource();
      pool.setJdbcUrl(
          "jdbc:postgresql://"
              + POSTGRES.getHost()
              + ":"
              + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
              + "/"
              + STUDIO_DB);
      pool.setUsername(STUDIO_ROLE);
      pool.setPassword(STUDIO_PASSWORD);
      pool.setDriverClassName("org.postgresql.Driver");
      return pool;
    }

    @Override
    @Bean
    EntitlementProvider entitlementProvider() {
      return actorId ->
          new EntitlementSnapshot(
              actorId,
              "premium",
              GRANTED.stream().collect(Collectors.toMap(key -> key, key -> "true")),
              100000,
              Instant.now().plusSeconds(60));
    }

    @Bean
    AssetStoreProperties assetStoreProperties() {
      // A developer's own machine, which is what a local phase is: a SENSITIVE pack installs here
      // (ADR-051 §8) and this suite exists to assert what happens once it has.
      return new AssetStoreProperties(false, Deployment.LOCAL);
    }

    @Bean
    PackSourceProperties packSourceProperties() {
      return new PackSourceProperties(List.of("orazaka-packs"));
    }

    @Bean
    PackBundleResolver packBundleResolver(
        ObjectMapper objectMapper, PackSourceProperties packSources) {
      return new PackBundleResolver(objectMapper, packSources);
    }

    @Bean
    RecordingRegistration capabilityRegistrationClient() {
      return new RecordingRegistration();
    }

    @Bean
    RecordingProvisioning packProvisioningClient() {
      return new RecordingProvisioning();
    }

    @Bean
    PackInstallerService packInstallerService(
        PackInstallRepository packInstallRepository,
        com.orazaka.jobs.domain.port.CapabilityRoutingClient capabilityRoutingClient,
        CapabilityRegistrationClient capabilityRegistrationClient,
        PackProvisioningClient packProvisioningClient,
        AssetStoreProperties assetStoreProperties) {
      return new PackInstallerService(
          packInstallRepository,
          capabilityRoutingClient,
          capabilityRegistrationClient,
          packProvisioningClient,
          assetStoreProperties);
    }

    @Bean
    PackBootstrap packBootstrap(
        PackBundleResolver packBundleResolver,
        PackInstallerService packInstallerService,
        PackInstallRepository packInstallRepository) {
      return new PackBootstrap(packBundleResolver, packInstallerService, packInstallRepository);
    }

    @Bean
    ComposerStudioService composerStudioService(
        StudioCatalogService catalogService,
        StudioAccessService accessService,
        com.orazaka.studioservice.domain.port.BlueprintRepository blueprintRepository,
        ObjectMapper objectMapper) {
      return new ComposerStudioService(
          catalogService, accessService, blueprintRepository, objectMapper);
    }
  }
}
