package com.krizaka.orazaka.studioservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.billing.domain.model.EntitlementSnapshot;
import com.krizaka.billing.domain.model.MeteredStep;
import com.krizaka.billing.domain.port.EntitlementProvider;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studioservice.domain.model.ComposerStudio;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.orazaka.test.architecture.Workspace;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The six media Studios of the real {@code orazaka-media} bundle run, and each is metered once (M2,
 * ADR-066).
 *
 * <p>Every capability in that pack was reachable before this run, and none of it was billed through
 * the composer: a button submitted a job, nothing held credits for it, and nothing settled them.
 * The claim here is the whole of M2's acceptance — <b>runnable as a Studio AND metered once</b> —
 * so it is asserted per Studio rather than inferred from one of them.
 *
 * <p>The blueprints are read from {@code orazaka-packs/orazaka-media/}, not inlined: this suite is
 * worth something only if it runs the definitions that ship. The executor and the credit client are
 * recorded stubs — what is under test is the engine and the pack, and a failure here must be
 * unambiguously one of those two (ADR-034 §16).
 *
 * <p>The installation is <b>derived</b> throughout: a TOOLKIT is had by entitlement, so the run
 * table's {@code installation_id} stays null and {@code studio_installation} stays empty.
 */
class MediaToolkitRunIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final String PACK = "orazaka-media";

  /**
   * Studio key → the inputs a run of it is started with, and the outcome its capability returns.
   */
  private static final Map<String, Map<String, Object>> INPUTS =
      Map.of(
          "image-generation", Map.of("prompt", "un atelier de menuiserie au petit matin"),
          "video-generation", Map.of("prompt", "travelling sur une façade haussmannienne"),
          "speech-synthesis", Map.of("text", "Bonjour, voici votre rendez-vous de demain."),
          "image-analysis", Map.of("assetId", "a1b2c3d4-0000-0000-0000-0000000000a1"),
          "audio-analysis", Map.of("assetId", "a1b2c3d4-0000-0000-0000-0000000000a2"),
          "video-analysis", Map.of("assetId", "a1b2c3d4-0000-0000-0000-0000000000a3"));

  /**
   * What each executor reports having consumed — the measurements of ADR-066 §4, in the names
   * {@code ConsumptionReport} carries. A step that measures nothing is RELEASED rather than billed,
   * which is exactly what these six did before this run, so an outcome with no consumption would
   * quietly assert the defect instead of its fix.
   */
  private static final Map<String, Map<String, Object>> CONSUMPTION =
      Map.of(
          "image-generation", Map.of("images", 1, "steps", 20, "width", 1024, "height", 1024),
          "video-generation", Map.of("frames", 150, "fps", 30),
          "speech-synthesis", Map.of("characters", 42),
          "image-analysis", Map.of("tokens", 1800),
          "audio-analysis", Map.of("audioSeconds", 132.48),
          "video-analysis", Map.of("audioSeconds", 90.5));

  /** The engine each step reports; the pricebook is keyed (capability, model). */
  private static final Map<String, String> ENGINES =
      Map.of(
          "image-generation", "sd-turbo",
          "video-generation", "ltx-video",
          "speech-synthesis", "piper-en-medium-ryan",
          "image-analysis", "llava:latest",
          "audio-analysis", "whisper-base",
          "video-analysis", "orazaka-video-analysis");

  private static final Map<String, Map<String, Object>> OUTCOMES =
      Map.of(
          "image-generation", Map.of("url", "/api/v1/assets/j/image.png", "format", "png"),
          "video-generation", Map.of("url", "/api/v1/assets/j/video.mp4", "format", "mp4"),
          "speech-synthesis", Map.of("url", "/api/v1/assets/j/speech.mp3", "format", "mp3"),
          "image-analysis", Map.of("analysis", "un salon clair, parquet chevron"),
          "audio-analysis", Map.of("analysis", "bonjour, voici votre rendez-vous"),
          "video-analysis", Map.of("transcript", "bonjour", "keyframeCount", 4));

  private static final Set<String> GRANTED = ConcurrentHashMap.newKeySet();
  private static final ObjectMapper MAPPER = new ObjectMapper();

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
  private static StudioCatalogService catalogService;
  private static StudioAccessService accessService;
  private static ComposerStudioService composerStudioService;
  private static StudioRunLifecycleIT.RecordingStepExecutionClient executor;
  private static StudioRunLifecycleIT.RecordingCreditClient credits;

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);
    context = new AnnotationConfigApplicationContext(MediaWiring.class);
    jdbcTemplate = context.getBean(JdbcTemplate.class);
    runService = context.getBean(StudioRunService.class);
    sagaService = context.getBean(RunSagaService.class);
    catalogService = context.getBean(StudioCatalogService.class);
    accessService = context.getBean(StudioAccessService.class);
    composerStudioService = context.getBean(ComposerStudioService.class);
    executor = context.getBean(StudioRunLifecycleIT.RecordingStepExecutionClient.class);
    credits = context.getBean(StudioRunLifecycleIT.RecordingCreditClient.class);
    installTheBundle();
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
    jdbcTemplate.update("DELETE FROM studio_outbox");
    executor.dispatched.clear();
    credits.aggregateSettlements.clear();
    credits.released.clear();
    GRANTED.clear();
  }

  @Test
  @DisplayName("every media Studio is included for an entitled actor, with no installation row")
  void everyStudioIsIncludedByEntitlementAlone() {
    INPUTS.keySet().forEach(key -> GRANTED.add("studio." + key));

    for (String studioKey : INPUTS.keySet()) {
      Studio studio = catalogService.find(studioKey, "fr").orElseThrow();
      assertEquals(PackKind.TOOLKIT, studio.kind(), studioKey + " carries its pack's kind");
      assertTrue(accessService.isIncluded(studio, ACTOR), studioKey + " is included");
    }
    assertEquals(
        0,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM studio_installation", Integer.class),
        "a derived installation writes no row");
  }

  @Test
  @DisplayName("the six are exactly the composer's button row, labelled from the pack's own i18n")
  void theSixAreTheComposerRow() {
    INPUTS.keySet().forEach(key -> GRANTED.add("studio." + key));

    List<ComposerStudio> row = composerStudioService.row("fr", ACTOR);

    // Derived from the blueprints that ship, not from a list: each of the six is one step over one
    // required input, which is what makes it launchable from a chat bar at all.
    assertEquals(
        INPUTS.keySet(),
        row.stream().map(ComposerStudio::studioKey).collect(Collectors.toSet()),
        "every media Studio belongs in a composer, and nothing else does");
    for (ComposerStudio entry : row) {
      assertTrue(
          entry.label() != null && !entry.label().isBlank(), entry.studioKey() + " is named");
      assertFalse(entry.locked(), entry.studioKey() + " is included for an entitled actor");
    }

    // What each button fills, declared by the pack rather than read off the key: the three
    // analysis Studios take the asset the user attached, the three generation ones take the prompt.
    Map<String, ComposerStudio.InputKind> kinds =
        row.stream()
            .collect(Collectors.toMap(ComposerStudio::studioKey, ComposerStudio::inputKind));
    assertEquals(ComposerStudio.InputKind.TEXT, kinds.get("image-generation"));
    assertEquals(ComposerStudio.InputKind.TEXT, kinds.get("video-generation"));
    assertEquals(ComposerStudio.InputKind.TEXT, kinds.get("speech-synthesis"));
    assertEquals(ComposerStudio.InputKind.ASSET, kinds.get("image-analysis"));
    assertEquals(ComposerStudio.InputKind.ASSET, kinds.get("audio-analysis"));
    assertEquals(ComposerStudio.InputKind.ASSET, kinds.get("video-analysis"));

    Map<String, String> inputKeys =
        row.stream().collect(Collectors.toMap(ComposerStudio::studioKey, ComposerStudio::inputKey));
    assertEquals(
        "text", inputKeys.get("speech-synthesis"), "the key is the schema's, not `prompt`");
    assertEquals("assetId", inputKeys.get("audio-analysis"));
  }

  @Test
  @DisplayName("each of the six runs one step to SUCCEEDED and settles exactly once")
  void eachStudioRunsAndIsMeteredOnce() {
    INPUTS.forEach(
        (studioKey, inputs) -> {
          GRANTED.add("studio." + studioKey);
          executor.dispatched.clear();
          credits.aggregateSettlements.clear();

          RunDetail run = runService.start(studioKey, ACTOR, inputs);
          assertEquals(RunStatus.RUNNING, run.status(), studioKey + " started");
          assertNull(run.installationId(), studioKey + " has no installation row to point at");
          assertEquals(1, executor.dispatched.size(), studioKey + " is one step, not two");

          sagaService.applyOutcome(
              executor.dispatched.get(0),
              OUTCOMES.get(studioKey),
              CONSUMPTION.get(studioKey),
              ENGINES.get(studioKey),
              null,
              null);

          assertEquals(
              RunStatus.SUCCEEDED,
              runService.find(run.id(), ACTOR).orElseThrow().status(),
              studioKey + " reached a terminal state");
          // Metered ONCE: one aggregate settlement for the run, carrying its one step. Twice would
          // be the double-billing ADR-044 closed; zero is what every one of these capabilities did
          // through door 1.
          assertEquals(1, credits.aggregateSettlements.size(), studioKey + " settled exactly once");
          List<MeteredStep> steps = credits.aggregateSettlements.get(0);
          assertEquals(1, steps.size(), studioKey + " settled its single step");
          assertEquals(
              ENGINES.get(studioKey),
              steps.get(0).modelName(),
              studioKey + " settled against the engine that ran, not the capability default");
          assertTrue(credits.released.isEmpty(), studioKey + " released nothing");
        });
  }

  /**
   * Seeds the catalogue from the bundle on disk — the pack row, its Studios and their published
   * blueprints — which is what {@code orazaka packs install} writes at runtime.
   *
   * <p>Read from {@code pack.yaml}'s neighbours rather than from a fixture: a suite that invents
   * its own blueprint proves the engine and says nothing about what ships.
   */
  private static void installTheBundle() {
    Path bundle = Workspace.packs(Path.of(System.getProperty("user.dir"))).resolve(PACK);
    jdbcTemplate.update(
        "INSERT INTO pack_category (category_key, icon_key, sort_weight, is_active)"
            + " VALUES ('business', 'studio', 0, true) ON CONFLICT DO NOTHING");
    jdbcTemplate.update(
        "INSERT INTO pack (pack_key, category_key, icon_key, regulatory_class, kind, status)"
            + " VALUES (?, 'business', 'image', 'STANDARD', 'TOOLKIT', 'PUBLISHED')"
            + " ON CONFLICT DO NOTHING",
        PACK);

    for (String studioKey : INPUTS.keySet()) {
      JsonNode blueprint =
          read(bundle.resolve("studios").resolve(studioKey).resolve("blueprint.json"));
      jdbcTemplate.update(
          "INSERT INTO studio (studio_key, label, profession, icon_key, pricing, pack_key,"
              + " entitlement_key, status, publisher_id, sort_weight, latest_version)"
              + " VALUES (?, ?, 'general', 'image', 'INCLUDED', ?, ?, 'PUBLISHED', 'orazaka', 0, ?)"
              + " ON CONFLICT (studio_key) DO NOTHING",
          studioKey,
          studioKey,
          PACK,
          "studio." + studioKey,
          blueprint.path("version").asString());
      jdbcTemplate.update(
          "INSERT INTO pack_studio (pack_key, studio_key, sort_weight) VALUES (?, ?, 0)"
              + " ON CONFLICT DO NOTHING",
          PACK,
          studioKey);
      jdbcTemplate.update(
          "INSERT INTO studio_blueprint (studio_key, version, status, definition, input_schema,"
              + " config_schema, estimated_credits, changelog, published_at, created_by)"
              + " VALUES (?, ?, 'PUBLISHED', ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, now(), 'orazaka')"
              + " ON CONFLICT (studio_key, version) DO NOTHING",
          studioKey,
          blueprint.path("version").asString(),
          blueprint.path("definition").toString(),
          blueprint.path("inputSchema").toString(),
          blueprint.path("configSchema").toString(),
          blueprint.path("estimatedCredits").asLong(),
          blueprint.path("changelog").asString(""));
    }
  }

  private static JsonNode read(Path blueprint) {
    try {
      return MAPPER.readTree(Files.readString(blueprint));
    } catch (java.io.IOException unreadable) {
      throw new IllegalStateException(
          "the orazaka-media bundle is what this suite runs; it must be readable", unreadable);
    }
  }

  static class MediaWiring extends StudioRunLifecycleIT.TestWiring {

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

    @Bean
    ComposerStudioService composerStudioService(
        StudioCatalogService catalogService,
        StudioAccessService accessService,
        com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository blueprintRepository,
        ObjectMapper objectMapper) {
      return new ComposerStudioService(
          catalogService, accessService, blueprintRepository, objectMapper);
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
  }
}
