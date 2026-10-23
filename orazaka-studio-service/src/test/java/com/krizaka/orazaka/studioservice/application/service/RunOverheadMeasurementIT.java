package com.krizaka.orazaka.studioservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studio.domain.model.StepDispatch;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence.PersistenceTestWiring;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import jdk.jfr.Recording;
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
  private static final String HOT_PATH_STUDIO_KEY = "m1-hot-path";
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
  private static final String HOT_PATH_DEFINITION =
      """
      {"studioKey":"m1-hot-path","version":"1.0.0","steps":[
       {"id":"stage1","kind":"CAPABILITY","featureKey":"orazaka.core.media.image","dependsOn":[],
        "inputs":{"prompt":"{{item}}"},"forEach":"{{inputs.photos}}","maxParallel":20,"out":"stage1",
        "onError":"FAIL","maxAttempts":1,"timeout":"PT2M"},
       {"id":"stage2","kind":"CAPABILITY","featureKey":"orazaka.core.media.image","dependsOn":["stage1"],
        "inputs":{"prompt":"{{inputs.photos}}"},"out":"stage2","onError":"FAIL","maxAttempts":1,"timeout":"PT2M"},
       {"id":"stage3","kind":"CAPABILITY","featureKey":"orazaka.core.media.image","dependsOn":["stage2"],
        "inputs":{"prompt":"{{inputs.photos}}"},"out":"stage3","onError":"FAIL","maxAttempts":1,"timeout":"PT2M"},
       {"id":"stage4","kind":"CAPABILITY","featureKey":"orazaka.core.media.image","dependsOn":["stage3"],
        "inputs":{"prompt":"{{inputs.photos}}"},"out":"stage4","onError":"FAIL","maxAttempts":1,"timeout":"PT2M"},
       {"id":"stage5","kind":"CAPABILITY","featureKey":"orazaka.core.media.image","dependsOn":["stage4"],
        "inputs":{"prompt":"{{inputs.photos}}"},"out":"stage5","onError":"FAIL","maxAttempts":1,"timeout":"PT2M"}],
       "outputs":[{"key":"result","label":"Result","from":"{{steps.stage5.url}}","type":"IMAGE"}]}
      """;
  private static final String HOT_PATH_INPUT_SCHEMA =
      "{\"type\":\"object\",\"required\":[\"photos\"],"
          + "\"properties\":{\"photos\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}";

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
  private static UUID hotPathInstallationId;

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
    seedHotPathStudio();
    installationId =
        context
            .getBean(StudioInstallationService.class)
            .install(STUDIO_KEY, ACTOR, Map.of(), "fr")
            .id();
    jdbcTemplate.update(
        "UPDATE studio_runtime_config SET config_value = '20' WHERE config_key = 'run.fan-out-max'");
    hotPathInstallationId =
        context
            .getBean(StudioInstallationService.class)
            .install(HOT_PATH_STUDIO_KEY, ACTOR, Map.of(), "fr")
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

  private static void seedHotPathStudio() {
    jdbcTemplate.update(
        "INSERT INTO studio (studio_key, label, tagline, profession, icon_key, pricing,"
            + " entitlement_key, status, publisher_id, latest_version, sort_weight)"
            + " VALUES (?, 'M1', 'Saga hot path', 'measurement', 'studio', 'FREE', ?, 'PUBLISHED',"
            + " 'orazaka', ?, 1) ON CONFLICT (studio_key) DO NOTHING",
        HOT_PATH_STUDIO_KEY,
        "studio." + HOT_PATH_STUDIO_KEY,
        VERSION);
    jdbcTemplate.update(
        "INSERT INTO studio_blueprint (studio_key, version, status, definition, input_schema,"
            + " config_schema, estimated_credits, changelog, published_at, created_by)"
            + " VALUES (?, ?, 'PUBLISHED', ?::jsonb, ?::jsonb, '{}'::jsonb, 5, 'm1', now(),"
            + " 'system') ON CONFLICT (studio_key, version) DO NOTHING",
        HOT_PATH_STUDIO_KEY,
        VERSION,
        HOT_PATH_DEFINITION,
        HOT_PATH_INPUT_SCHEMA);
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

  @Test
  @DisplayName("[M1] measure five steps with a 20-item fan-out before and after saga tuning")
  void measureFiveStepFanOut() {
    List<Long> outcomeMicros = new ArrayList<>();
    List<String> queryCounts = new ArrayList<>();
    int warmups = 10;
    int samples = 50;
    List<String> photos =
        java.util.stream.IntStream.range(0, 20).mapToObj(i -> "photo-" + i).toList();

    Recording recording = startJfrRecording();
    try {
      for (int sample = 0; sample < warmups + samples; sample++) {
        if (sample == warmups - 1) {
          counter.begin();
        }
        RunDetail run = runService.start(hotPathInstallationId, ACTOR, Map.of("photos", photos));
        if (sample == warmups - 1) {
          List<String> startStatements = counter.end();
          long declarationReads =
              // The pack's declarations (scope guard, safety), not every pack_studio join: the
              // start also reads the regulatory class once, which is not a dispatch read.
              startStatements.stream()
                  .filter(sql -> sql.contains("p.scope_guard") || sql.contains("p.safety"))
                  .count();
          assertEquals(1, declarationReads, "fan-out dispatch reads pack declarations once");
          queryCounts.add("fanout-start-declaration-reads|" + declarationReads);
        }
        List<String> fanOutJobs =
            jdbcTemplate.queryForList(
                "SELECT job_id FROM studio_run_step WHERE run_id = ? AND step_id = 'stage1'"
                    + " ORDER BY ordinal",
                String.class,
                run.id());
        assertEquals(20, fanOutJobs.size(), "the first stage must fan out to 20 jobs");
        if (sample == warmups - 1) {
          queryCounts.add(
              "job-index-plan|"
                  + jdbcTemplate
                      .queryForList(
                          "EXPLAIN (ANALYZE, BUFFERS) SELECT run_id, step_id, ordinal"
                              + " FROM studio_run_step"
                              + " WHERE job_id = ?",
                          String.class,
                          fanOutJobs.getFirst())
                      .stream()
                      .collect(java.util.stream.Collectors.joining(" / ")));
          queryCounts.add(
              "run-snapshot-plan|"
                  + jdbcTemplate
                      .queryForList(
                          "EXPLAIN (ANALYZE, BUFFERS) SELECT step_id, ordinal, status,"
                              + " failure_cause, error_message,"
                              + " finished_at, output FROM studio_run_step WHERE run_id = ?"
                              + " ORDER BY step_id, ordinal",
                          String.class,
                          run.id())
                      .stream()
                      .collect(java.util.stream.Collectors.joining(" / ")));
        }

        for (int i = 0; i < fanOutJobs.size(); i++) {
          boolean record = sample == warmups - 1 && (i == 0 || i == fanOutJobs.size() - 1);
          if (record) {
            counter.begin();
          }
          long started = System.nanoTime();
          sagaService.applyOutcome(fanOutJobs.get(i), Map.of("url", "/media/m1.png"), null);
          long elapsed = System.nanoTime() - started;
          if (sample >= warmups) {
            outcomeMicros.add(elapsed / 1000);
          }
          if (record) {
            List<String> statements = counter.end();
            int expected = i == 0 ? 3 : 5;
            assertEquals(
                expected,
                statements.size(),
                "one outcome snapshot reads all states, causes, and outputs in one query");
            queryCounts.add(
                "stage1-" + (i == 0 ? "intermediate" : "last") + "|" + statements.size());
          }
        }

        for (int stage = 2; stage <= 5; stage++) {
          String stepId = "stage" + stage;
          String jobId =
              jdbcTemplate.queryForObject(
                  "SELECT job_id FROM studio_run_step WHERE run_id = ? AND step_id = ?",
                  String.class,
                  run.id(),
                  stepId);
          boolean record = sample == warmups - 1 && stage == 5;
          if (record) {
            counter.begin();
          }
          long started = System.nanoTime();
          sagaService.applyOutcome(jobId, Map.of("url", "/media/m1.png"), null);
          long elapsed = System.nanoTime() - started;
          if (sample >= warmups) {
            outcomeMicros.add(elapsed / 1000);
          }
          if (record) {
            queryCounts.add("stage5-terminal|" + counter.end().size());
          }
        }

        assertEquals(
            RunStatus.SUCCEEDED,
            runService.find(run.id(), ACTOR).orElseThrow().status(),
            "the five-step run completes");
      }
    } finally {
      finishJfrRecording(recording);
    }

    List<Long> ordered = outcomeMicros.stream().sorted().toList();
    long p95 = ordered.get((int) Math.ceil(ordered.size() * 0.95) - 1);
    String result =
        "samples="
            + samples
            + ", outcomes="
            + outcomeMicros.size()
            + ", p95_advance_us="
            + p95
            + ", query_counts="
            + queryCounts;
    System.out.println("[M1] " + result);
    writeHotPathMeasurement(result, outcomeMicros);
  }

  @Test
  @DisplayName("The published-blueprint cache size takes effect without restarting the service")
  void blueprintCacheSizeIsReadLive() {
    List<String> photos =
        java.util.stream.IntStream.range(0, 20).mapToObj(i -> "photo-" + i).toList();
    RunDetail run = runService.start(hotPathInstallationId, ACTOR, Map.of("photos", photos));
    List<String> fanOutJobs =
        jdbcTemplate.queryForList(
            "SELECT job_id FROM studio_run_step WHERE run_id = ? AND step_id = 'stage1'"
                + " ORDER BY ordinal",
            String.class,
            run.id());

    jdbcTemplate.update(
        "UPDATE studio_runtime_config SET config_value = '0' WHERE config_key = ?",
        "run.blueprint-cache-size");
    try {
      counter.begin();
      sagaService.applyOutcome(fanOutJobs.getFirst(), Map.of("url", "/media/m1.png"), null);
      assertTrue(
          counter.end().stream().anyMatch(sql -> sql.contains("FROM studio_blueprint")),
          "zero disables the cache immediately");
    } finally {
      jdbcTemplate.update(
          "UPDATE studio_runtime_config SET config_value = '100' WHERE config_key = ?",
          "run.blueprint-cache-size");
    }

    for (String jobId : fanOutJobs.subList(1, fanOutJobs.size())) {
      sagaService.applyOutcome(jobId, Map.of("url", "/media/m1.png"), null);
    }
    for (int stage = 2; stage <= 5; stage++) {
      String jobId =
          jdbcTemplate.queryForObject(
              "SELECT job_id FROM studio_run_step WHERE run_id = ? AND step_id = ?",
              String.class,
              run.id(),
              "stage" + stage);
      sagaService.applyOutcome(jobId, Map.of("url", "/media/m1.png"), null);
    }
    assertEquals(RunStatus.SUCCEEDED, runService.find(run.id(), ACTOR).orElseThrow().status());
  }

  private static void writeHotPathMeasurement(String summary, List<Long> outcomeMicros) {
    StringBuilder out = new StringBuilder("# M1 five-step / fan-out-20 measurement\n");
    out.append(summary).append('\n');
    out.append("# outcome_advance_us\n");
    outcomeMicros.forEach(micros -> out.append(micros).append('\n'));
    Path target = Path.of("target", "m1-saga-hot-path.txt");
    try {
      Files.createDirectories(target.getParent());
      Files.writeString(target, out.toString());
    } catch (IOException unwritable) {
      throw new UncheckedIOException("cannot write " + target, unwritable);
    }
    System.out.println("M1 raw samples written to " + target.toAbsolutePath());
  }

  private static Recording startJfrRecording() {
    Recording recording = new Recording();
    recording.setName("studio-saga-hot-path");
    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
    recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1));
    recording.start();
    return recording;
  }

  private static void finishJfrRecording(Recording recording) {
    Path target = Path.of("target", "m1-saga-hot-path.jfr");
    try {
      recording.stop();
      Files.createDirectories(target.getParent());
      recording.dump(target);
    } catch (IOException unwritable) {
      throw new UncheckedIOException("cannot write " + target, unwritable);
    } finally {
      recording.close();
    }
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
