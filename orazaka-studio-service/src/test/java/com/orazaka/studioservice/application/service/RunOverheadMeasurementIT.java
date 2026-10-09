package com.orazaka.studioservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.studio.domain.model.RunStatus;
import com.orazaka.studio.domain.model.StepDispatch;
import com.orazaka.studioservice.domain.model.RunDetail;
import com.orazaka.studioservice.infrastructure.adapter.persistence.PersistenceTestWiring;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.orazaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Phase M0 — what the run saga costs on a <b>single-step</b> run, in latency and in SQL.
 *
 * <p>The decision this feeds is in {@code docs/UNIFIED_PACK_SURFACE.md}: whether every deferred
 * capability can become a one-step Studio run. The thresholds were written into {@code
 * docs/measurements/M0-run-overhead.md} before this file existed.
 *
 * <p><b>What is measured, and why it is an upper bound rather than a difference.</b> The workflow
 * asks for {@code (A₂+B₂) − (A₁+B₁)} — door 2's overhead minus door 1's. Door 1 lives in
 * conversation-service, behind a JPA stack this module does not have on its classpath, so measuring
 * it here would mean a second Testcontainers harness in another module. It is not needed: door 1
 * does strictly positive work (a credit hold, an {@code orazaka_jobs} insert, an outbox insert, and
 * a relay poll), so {@code (A₂+B₂) − (A₁+B₁) < A₂+B₂}. Measuring door 2's <i>total</i> therefore
 * bounds the overhead from above, and it errs in the direction that would make the decision STOP —
 * never in the direction that licenses the next five phases.
 *
 * <p><b>The executor is neutralised on purpose (§4.1).</b> {@code RecordingStepExecutionClient}
 * returns a job id and does nothing, so segments A and B are the only things varying. An MLX image
 * takes ~67 s on this machine with run-to-run variance in the seconds; an 800 ms saga overhead sits
 * inside that variance, and ten paired end-to-end samples could not have resolved it.
 *
 * <p>This suite asserts that the measurement is <i>valid</i> — enough samples, every run terminal,
 * statements actually recorded — and not that the number is good. A harness that reddens when the
 * number it produces is unwelcome puts pressure on the number.
 */
class RunOverheadMeasurementIT {

  /** §4.1 asks for ≥ 30; 50 costs nothing here and makes the tail legible. */
  private static final int SAMPLES = 50;

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final String STUDIO_KEY = "m0-single-step";
  private static final String VERSION = "1.0.0";

  private static final String DEFINITION =
      """
      {"studioKey":"m0-single-step","version":"1.0.0",
       "steps":[{"id":"generate","kind":"CAPABILITY","featureKey":"orazaka.core.media.image",
                 "dependsOn":[],"inputs":{"prompt":"{{inputs.prompt}}"},"out":"image",
                 "onError":"FAIL","maxAttempts":1,"timeout":"PT2M"}],
       "outputs":[{"key":"image","label":"Image","from":"{{steps.generate.url}}","type":"IMAGE"}]}
      """;
  private static final String INPUT_SCHEMA =
      "{\"type\":\"object\",\"required\":[\"prompt\"],"
          + "\"properties\":{\"prompt\":{\"type\":\"string\"}}}";

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
  private static TimingStepExecutionClient executor;
  private static CountingDataSource counter;
  private static UUID installationId;

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);
    context = new AnnotationConfigApplicationContext(MeasurementWiring.class);
    jdbcTemplate = context.getBean(JdbcTemplate.class);
    runService = context.getBean(StudioRunService.class);
    sagaService = context.getBean(RunSagaService.class);
    executor =
        (TimingStepExecutionClient)
            context.getBean(StudioRunLifecycleIT.RecordingStepExecutionClient.class);
    counter = (CountingDataSource) context.getBean(DataSource.class);
    seedSingleStepStudio();
    installationId =
        context
            .getBean(StudioInstallationService.class)
            .install(STUDIO_KEY, ACTOR, Map.of(), "fr")
            .id();
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  private static void seedSingleStepStudio() {
    jdbcTemplate.update(
        "INSERT INTO studio (studio_key, label, tagline, profession, icon_key, pricing,"
            + " entitlement_key, status, publisher_id, latest_version, sort_weight)"
            + " VALUES (?, 'M0', 'Single step', 'measurement', 'studio', 'FREE', ?, 'PUBLISHED',"
            + " 'orazaka', ?, 1) ON CONFLICT (studio_key) DO NOTHING",
        STUDIO_KEY,
        "studio." + STUDIO_KEY,
        VERSION);
    jdbcTemplate.update(
        "INSERT INTO studio_blueprint (studio_key, version, status, definition, input_schema,"
            + " config_schema, estimated_credits, changelog, published_at, created_by)"
            + " VALUES (?, ?, 'PUBLISHED', ?::jsonb, ?::jsonb, '{}'::jsonb, 5, 'm0', now(),"
            + " 'system') ON CONFLICT (studio_key, version) DO NOTHING",
        STUDIO_KEY,
        VERSION,
        DEFINITION,
        INPUT_SCHEMA);
  }

  /** One sample: door 2's segment A, then its segment B, with the run left terminal. */
  private long[] sample() {
    int before = executor.dispatched.size();
    long t0 = System.nanoTime();
    RunDetail run = runService.start(installationId, ACTOR, Map.of("prompt", "a measured prompt"));
    long startReturned = System.nanoTime();

    assertEquals(RunStatus.RUNNING, run.status(), "the run must be live when start() returns");
    assertEquals(before + 1, executor.dispatched.size(), "a single-step run dispatches once");
    String jobId = executor.dispatched.get(executor.dispatched.size() - 1);

    long t1 = System.nanoTime();
    sagaService.applyOutcome(jobId, Map.of("url", "/media/m0.png"), null);
    long outcomeApplied = System.nanoTime();

    RunDetail finished = runService.find(run.id(), ACTOR).orElseThrow();
    assertEquals(RunStatus.SUCCEEDED, finished.status(), "segment B must leave the run terminal");

    return new long[] {
      executor.submittedAtNanos - t0, startReturned - t0, outcomeApplied - t1,
    };
  }

  @Test
  @DisplayName("[M0] the saga's own cost on a single-step run — latency and statements")
  void measure() {
    List<long[]> samples = new ArrayList<>();
    for (int i = 0; i < SAMPLES; i++) {
      samples.add(sample());
    }
    assertTrue(samples.size() >= 30, "§4.1 asks for at least 30 samples per door");

    counter.begin();
    int before = executor.dispatched.size();
    RunDetail counted =
        runService.start(installationId, ACTOR, Map.of("prompt", "the counted prompt"));
    List<String> segmentA = counter.end();

    counter.begin();
    sagaService.applyOutcome(executor.dispatched.get(before), Map.of("url", "/media/m0.png"), null);
    List<String> segmentB = counter.end();

    assertEquals(
        RunStatus.SUCCEEDED,
        runService.find(counted.id(), ACTOR).orElseThrow().status(),
        "the counted run must complete like every other");
    assertTrue(!segmentA.isEmpty() && !segmentB.isEmpty(), "the counter recorded nothing");

    write(samples, segmentA, segmentB);
  }

  /**
   * Writes the raw samples where the report can quote them.
   *
   * <p>{@code target/} rather than {@code docs/}: the report is written by hand from this file, and
   * a test that edits documentation would make the documentation a build artefact.
   */
  private static void write(List<long[]> samples, List<String> segmentA, List<String> segmentB) {
    StringBuilder out = new StringBuilder();
    out.append("# M0 raw samples — micros. dispatch_us,startReturned_us,segmentB_us\n");
    for (long[] sample : samples) {
      out.append(sample[0] / 1000)
          .append(',')
          .append(sample[1] / 1000)
          .append(',')
          .append(sample[2] / 1000)
          .append('\n');
    }
    out.append("\n# Segment A statements (").append(segmentA.size()).append(")\n");
    segmentA.forEach(sql -> out.append("A| ").append(sql).append('\n'));
    out.append("\n# Segment B statements (").append(segmentB.size()).append(")\n");
    segmentB.forEach(sql -> out.append("B| ").append(sql).append('\n'));
    Path target = Path.of("target", "m0-run-overhead.txt");
    try {
      Files.createDirectories(target.getParent());
      Files.writeString(target, out.toString());
    } catch (IOException unwritable) {
      throw new UncheckedIOException("cannot write " + target, unwritable);
    }
    System.out.println("M0 raw samples written to " + target.toAbsolutePath());
  }

  /** Records when the engine handed the command over — segment A's exit. */
  static class TimingStepExecutionClient extends StudioRunLifecycleIT.RecordingStepExecutionClient {
    volatile long submittedAtNanos;

    @Override
    public String submit(StepDispatch dispatch) {
      String jobId = super.submit(dispatch);
      submittedAtNanos = System.nanoTime();
      return jobId;
    }
  }

  /**
   * The lifecycle suite's wiring, with the DataSource wrapped and the executor timed.
   *
   * <p>Extended rather than copied: the engine under measurement must be wired exactly as the suite
   * that asserts its behaviour wires it, or the number describes a different object.
   */
  @Configuration
  // Class proxies, as Spring Boot creates them in production: a service that implements a port
  // (OutboxService is an OutboxStore) must still be injectable by its class.
  @EnableTransactionManagement(proxyTargetClass = true)
  @Import(PersistenceTestWiring.class)
  static class MeasurementWiring extends StudioRunLifecycleIT.TestWiring {

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
      return new CountingDataSource(pool);
    }

    @Override
    @Bean
    StudioRunLifecycleIT.RecordingStepExecutionClient stepExecutionClient() {
      return new TimingStepExecutionClient();
    }
  }
}
