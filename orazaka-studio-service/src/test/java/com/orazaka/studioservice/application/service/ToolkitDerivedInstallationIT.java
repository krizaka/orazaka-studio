package com.orazaka.studioservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.test.container.ServiceRoles;
import com.orazaka.billing.domain.model.EntitlementSnapshot;
import com.orazaka.billing.domain.port.EntitlementProvider;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.RunStatus;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studioservice.domain.exception.StudioIncludedException;
import com.orazaka.studioservice.domain.exception.StudioNotInstalledException;
import com.orazaka.studioservice.domain.model.RunDetail;
import com.orazaka.studioservice.domain.port.BlueprintRepository;
import com.orazaka.studioservice.infrastructure.adapter.persistence.PersistenceTestWiring;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * ADR-061 — a TOOLKIT's installation is derived, and the database is what makes that safe.
 *
 * <p>Against the real {@code infra/initdb/80-studio.sql}, because three of the four claims are
 * about the schema: the nullable {@code studio_run.installation_id}, the CHECKs that refuse what a
 * TOOLKIT cannot take, and the {@code blueprint_version} column that makes resolve-once a property
 * of the table rather than a promise of a method.
 *
 * <p>Blueprints are written through {@link BlueprintPublishService} — {@code saveDraft} then {@code
 * publish} — and not inserted. The mid-run test is about what a real publication does to a run in
 * flight, and a row inserted by hand is a claim about intent.
 */
class ToolkitDerivedInstallationIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  private static final String TOOLKIT_PACK = "media-toolkit";
  private static final String VERTICAL_PACK = "realestate-studio";
  private static final String TOOLKIT_STUDIO = "image-generation";
  private static final String FLOATING_STUDIO = "image-variation";
  private static final String VERTICAL_STUDIO = "realestate-reels";

  /** Entitlement keys this actor holds, changed per test; the snapshot is always resolved. */
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
  private static StudioInstallationService installationService;
  private static StudioCatalogService catalogService;
  private static StudioAccessService accessService;
  private static BlueprintPublishService publishService;
  private static StudioRunLifecycleIT.RecordingStepExecutionClient executor;

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);
    context = new AnnotationConfigApplicationContext(ToolkitWiring.class);
    jdbcTemplate = context.getBean(JdbcTemplate.class);
    runService = context.getBean(StudioRunService.class);
    sagaService = context.getBean(RunSagaService.class);
    installationService = context.getBean(StudioInstallationService.class);
    catalogService = context.getBean(StudioCatalogService.class);
    accessService = context.getBean(StudioAccessService.class);
    publishService = context.getBean(BlueprintPublishService.class);
    executor = context.getBean(StudioRunLifecycleIT.RecordingStepExecutionClient.class);

    seedCatalogue();
    publish(TOOLKIT_STUDIO, "1.0.0", "v1");
    publish(VERTICAL_STUDIO, "1.0.0", "v1");
    publish(FLOATING_STUDIO, "1.0.0", "v1");
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
    jdbcTemplate.update("DELETE FROM studio_outbox");
    executor.dispatched.clear();
    GRANTED.clear();
  }

  private static void seedCatalogue() {
    jdbcTemplate.update(
        "INSERT INTO pack_category (category_key, icon_key, sort_weight, is_active)"
            + " VALUES ('business', 'studio', 0, true) ON CONFLICT DO NOTHING");
    jdbcTemplate.update(
        "INSERT INTO pack (pack_key, category_key, icon_key, regulatory_class, kind, status)"
            + " VALUES (?, 'business', 'image', 'STANDARD', 'TOOLKIT', 'PUBLISHED'),"
            + "        (?, 'business', 'studio', 'STANDARD', 'VERTICAL', 'PUBLISHED')",
        TOOLKIT_PACK,
        VERTICAL_PACK);
    for (String[] studio :
        new String[][] {
          {TOOLKIT_STUDIO, TOOLKIT_PACK},
          {FLOATING_STUDIO, TOOLKIT_PACK},
          {VERTICAL_STUDIO, VERTICAL_PACK}
        }) {
      jdbcTemplate.update(
          "INSERT INTO studio (studio_key, label, profession, icon_key, pricing, pack_key,"
              + " entitlement_key, status, publisher_id, sort_weight)"
              + " VALUES (?, ?, 'media', 'image', 'PAID', ?, ?, 'PUBLISHED', 'orazaka', 0)",
          studio[0],
          studio[0],
          studio[1],
          "studio." + studio[0]);
      jdbcTemplate.update(
          "INSERT INTO pack_studio (pack_key, studio_key, sort_weight) VALUES (?, ?, 0)",
          studio[1],
          studio[0]);
    }
  }

  /**
   * A two-step blueprint whose second step's prompt names its version, so the step a run dispatches
   * says which definition produced it.
   */
  private static void publish(String studioKey, String version, String label) {
    String definition =
        """
        {"studioKey":"%s","version":"%s",
         "steps":[
           {"id":"first","kind":"CAPABILITY","featureKey":"orazaka.core.media.image",
            "dependsOn":[],"inputs":{"prompt":"%s first"},"out":"firstOut",
            "onError":"FAIL","maxAttempts":1,"timeout":"PT2M"},
           {"id":"second","kind":"CAPABILITY","featureKey":"orazaka.core.media.image",
            "dependsOn":["first"],"inputs":{"prompt":"%s second"},"out":"secondOut",
            "onError":"FAIL","maxAttempts":1,"timeout":"PT2M"}],
         "outputs":[{"key":"image","label":"Image","from":"{{steps.second.url}}","type":"IMAGE"}]}
        """
            .formatted(studioKey, version, label, label);
    publishService.saveDraft(
        studioKey, version, definition, "{\"type\":\"object\"}", "{}", 5, label, "system");
    publishService.publish(studioKey, version);
  }

  private static int installationRows() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM studio_installation", Integer.class);
  }

  private static String promptOfLastDispatch() {
    return String.valueOf(executor.lastInputs.get("prompt"));
  }

  // ── Gate 5.1 ─────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "[ADR-061 5.1] an entitled actor has a TOOLKIT and runs it with zero installation rows")
  void entitledActorRunsAToolkitWithNoInstallationRow() {
    GRANTED.add("studio." + TOOLKIT_STUDIO);
    Studio studio = catalogService.find(TOOLKIT_STUDIO, "fr").orElseThrow();

    assertEquals(PackKind.TOOLKIT, studio.kind(), "the Studio carries its pack's kind");
    assertTrue(accessService.isIncluded(studio, ACTOR), "entitlement alone is the installation");

    RunDetail run = runService.start(TOOLKIT_STUDIO, ACTOR, Map.of());
    assertEquals(RunStatus.RUNNING, run.status());
    assertNull(run.installationId(), "a derived installation has no row to point at");

    sagaService.applyOutcome(executor.dispatched.get(0), Map.of("url", "/media/a.png"), null);
    sagaService.applyOutcome(executor.dispatched.get(1), Map.of("url", "/media/b.png"), null);
    assertEquals(RunStatus.SUCCEEDED, runService.find(run.id(), ACTOR).orElseThrow().status());

    assertEquals(0, installationRows(), "no studio_installation row was read into existence");
  }

  @Test
  @DisplayName("[ADR-061] installing a TOOLKIT is refused and writes nothing")
  void installingAToolkitIsRefusedAndWritesNoRow() {
    GRANTED.add("studio." + TOOLKIT_STUDIO);

    StudioIncludedException refused =
        assertThrows(
            StudioIncludedException.class,
            () -> installationService.install(TOOLKIT_STUDIO, ACTOR, Map.of(), "fr"));

    assertEquals(TOOLKIT_PACK, refused.packKey());
    assertEquals(0, installationRows());
  }

  // ── Gate 5.2 ─────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "[ADR-061 5.2] an unentitled TOOLKIT and an uninstalled VERTICAL both refuse with the pack")
  void unentitledToolkitAndUninstalledVerticalRefuseAlike() {
    GRANTED.add("studio." + VERTICAL_STUDIO);

    StudioNotEntitledException unentitled =
        assertThrows(
            StudioNotEntitledException.class,
            () -> runService.start(TOOLKIT_STUDIO, ACTOR, Map.of()));
    StudioNotInstalledException uninstalled =
        assertThrows(
            StudioNotInstalledException.class,
            () -> runService.start(VERTICAL_STUDIO, ACTOR, Map.of()));

    assertEquals(TOOLKIT_PACK, unentitled.packKey());
    assertEquals(VERTICAL_PACK, uninstalled.packKey());
    assertTrue(executor.dispatched.isEmpty(), "neither refusal took a hold or dispatched a step");
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM studio_run", Integer.class));
  }

  @Test
  @DisplayName(
      "[ADR-061] a VERTICAL run by Studio key goes through its installation, pinned as before")
  void anInstalledVerticalRunsByKeyThroughItsInstallation() {
    GRANTED.add("studio." + VERTICAL_STUDIO);
    UUID installed = installationService.install(VERTICAL_STUDIO, ACTOR, Map.of(), "fr").id();

    RunDetail run = runService.start(VERTICAL_STUDIO, ACTOR, Map.of());

    assertEquals(installed, run.installationId());
    assertEquals("1.0.0", run.blueprintVersion());
  }

  // ── Gate 5.3 ─────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "[ADR-061 5.3] a version published between two steps is not the version the run executes")
  void publishingMidRunDoesNotChangeWhatTheRunExecutes() {
    GRANTED.add("studio." + FLOATING_STUDIO);

    RunDetail run = runService.start(FLOATING_STUDIO, ACTOR, Map.of());
    assertEquals(
        "1.0.0", run.blueprintVersion(), "resolved once, at start, and written on the run");
    assertEquals("v1 first", promptOfLastDispatch());

    // A real publication, between the first step's dispatch and its outcome.
    publish(FLOATING_STUDIO, "2.0.0", "v2");
    assertEquals(
        "2.0.0",
        catalogService.latestPublished(FLOATING_STUDIO).orElseThrow().version(),
        "the publication happened — the next resolution would pick it");

    sagaService.applyOutcome(executor.dispatched.get(0), Map.of("url", "/media/a.png"), null);

    assertEquals(
        "v1 second",
        promptOfLastDispatch(),
        "the second step came from the version the run started on, not the one published since");
    RunDetail inFlight = runService.find(run.id(), ACTOR).orElseThrow();
    assertEquals("1.0.0", inFlight.blueprintVersion());

    sagaService.applyOutcome(executor.dispatched.get(1), Map.of("url", "/media/b.png"), null);
    assertEquals(RunStatus.SUCCEEDED, runService.find(run.id(), ACTOR).orElseThrow().status());

    RunDetail next = runService.start(FLOATING_STUDIO, ACTOR, Map.of());
    assertEquals("2.0.0", next.blueprintVersion(), "a TOOLKIT floats: the next run takes v2");
    assertEquals("v2 first", promptOfLastDispatch());
  }

  // ── Gate 5.4 ─────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("[ADR-061 5.4] the database refuses a REGULATED TOOLKIT, whatever wrote the row")
  void theDatabaseRefusesARegulatedToolkit() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbcTemplate.update(
                    "INSERT INTO pack (pack_key, category_key, icon_key, regulatory_class, kind)"
                        + " VALUES ('wellbeing-toolkit', 'business', 'heart', 'REGULATED',"
                        + " 'TOOLKIT')"));

    assertTrue(
        refused.getMessage().contains("ck_pack_toolkit_not_regulated"), refused.getMessage());
  }

  @Test
  @DisplayName(
      "[ADR-061] the database refuses a TOOLKIT declaring safety, whose reply needs a region")
  void theDatabaseRefusesAToolkitThatDeclaresSafety() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbcTemplate.update(
                    "INSERT INTO pack (pack_key, category_key, icon_key, regulatory_class, kind,"
                        + " safety) VALUES ('legal-toolkit', 'business', 'scale', 'SENSITIVE',"
                        + " 'TOOLKIT', '{}'::jsonb)"));

    assertTrue(
        refused.getMessage().contains("ck_pack_toolkit_no_installation_controls"),
        refused.getMessage());
  }

  @Test
  @DisplayName("[ADR-061] a run with no installation cannot carry REGULATED data")
  void theDatabaseRefusesAnUninstalledRegulatedRun() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbcTemplate.update(
                    "INSERT INTO studio_run (actor_id, studio_key, blueprint_version, status,"
                        + " correlation_id, data_class)"
                        + " VALUES (?, ?, '1.0.0', 'RUNNING', 'c', 'REGULATED')",
                    ACTOR,
                    TOOLKIT_STUDIO));

    assertTrue(
        refused.getMessage().contains("ck_run_uninstalled_not_regulated"), refused.getMessage());
  }

  /**
   * The lifecycle suite's wiring, with a resolved entitlement snapshot this suite controls and the
   * real publish service.
   */
  @Configuration
  // Class proxies, as Spring Boot creates them in production: a service that implements a port
  // (OutboxService is an OutboxStore) must still be injectable by its class.
  @EnableTransactionManagement(proxyTargetClass = true)
  @Import(PersistenceTestWiring.class)
  static class ToolkitWiring extends StudioRunLifecycleIT.TestWiring {

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
              1000,
              Instant.now().plusSeconds(60));
    }

    @Bean
    BlueprintPublishService blueprintPublishService(
        JdbcTemplate jdbcTemplate,
        BlueprintRepository blueprintRepository,
        StudioCatalogService catalogService,
        CapabilityRoutingClient capabilityRoutingClient) {
      return new BlueprintPublishService(
          jdbcTemplate, blueprintRepository, catalogService, capabilityRoutingClient);
    }
  }
}
