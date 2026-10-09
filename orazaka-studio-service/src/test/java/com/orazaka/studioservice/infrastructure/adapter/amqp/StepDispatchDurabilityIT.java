package com.orazaka.studioservice.infrastructure.adapter.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.test.container.ServiceRoles;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studio.domain.model.StepDispatch;
import com.orazaka.studioservice.application.service.OutboxService;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * A step dispatch is durable with the state that describes it (ADR-067, M2.5 §2).
 *
 * <p>`RunSagaService.advance` is `@Transactional` and the adapter used to call {@code
 * convertAndSend} from inside it. That is the dual-write bug, and on this path it is the worse half
 * of it: a rollback after the send left a worker executing against a step row that no longer
 * existed, and the credit hold it had taken lives in the <b>billing service</b>, where a rollback
 * here cannot reach it — hold taken, run gone, accelerator busy, aggregate settle never called.
 *
 * <p>Both directions are asserted, because either alone passes for the wrong reason: a committed
 * dispatch leaves exactly one row to publish, carrying the exchange, the routing key and the job id
 * the consumer dedups on; a rolled-back one leaves nothing at all.
 */
class StepDispatchDurabilityIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";
  private static final UUID RUN = UUID.randomUUID();

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

  private static JdbcTemplate jdbcTemplate;
  private static TransactionTemplate transactions;
  private static AmqpStepExecutionAdapter adapter;

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);

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
    DataSource dataSource = pool;

    jdbcTemplate = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    CapabilityRoutingClient routing =
        featureKey ->
            Optional.of(
                new CapabilityRoute(
                    featureKey, "job.media.generate", null, "IMAGE", "BATCH", true));
    adapter =
        new AmqpStepExecutionAdapter(new OutboxService(jdbcTemplate, new ObjectMapper()), routing);
  }

  @AfterAll
  static void stopContainer() {
    POSTGRES.stop();
  }

  @BeforeEach
  void reset() {
    jdbcTemplate.update("DELETE FROM studio_outbox");
  }

  @Test
  @DisplayName("a committed dispatch leaves exactly one row to publish, addressed and identified")
  void aCommittedDispatchIsQueuedForTheRelay() {
    String jobId = transactions.execute(status -> adapter.submit(dispatch()));

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT aggregate_id, event_type, exchange, message_id, published_at FROM studio_outbox");
    assertThat(row.get("aggregate_id")).isEqualTo(RUN.toString());
    assertThat(row.get("exchange"))
        .as("commands go to the jobs exchange")
        .isEqualTo("orazaka.jobs");
    assertThat(row.get("event_type"))
        .as("the capability's routing key")
        .isEqualTo("job.media.generate");
    assertThat(row.get("message_id"))
        .as("the id the job service dedups a redelivery by")
        .isEqualTo(jobId);
    assertThat(row.get("published_at")).as("nothing is published inside the transaction").isNull();
  }

  @Test
  @DisplayName("a dispatch whose transaction rolls back leaves nothing to publish")
  void aRolledBackDispatchIsNeverPublished() {
    assertThatThrownBy(
            () ->
                transactions.execute(
                    status -> {
                      adapter.submit(dispatch());
                      // What `advance` does when a later step of the same transaction fails.
                      throw new IllegalStateException("the saga rolled back");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM studio_outbox", Integer.class))
        .as("a job the run no longer knows about must never reach a worker")
        .isZero();
  }

  @Test
  @DisplayName("what durability costs: one INSERT where there was one synchronous publish")
  void theAddedCostIsOneInsert() {
    // M0 measured the saga at p95 30.5 ms against a 250 ms threshold, and its harness stubs the
    // executor — so it cannot see this adapter at all. This is the piece M0 cannot measure: the
    // marginal cost of making a dispatch durable, measured where it actually happens (M2.5 §2.2).
    int samples = 50;
    long[] micros = new long[samples];
    for (int i = 0; i < samples; i++) {
      long started = System.nanoTime();
      transactions.execute(status -> adapter.submit(dispatch()));
      micros[i] = (System.nanoTime() - started) / 1000;
    }
    java.util.Arrays.sort(micros);
    long p50 = micros[samples / 2];
    long p95 = micros[(int) Math.ceil(0.95 * samples) - 1];
    System.out.printf(
        "M2.5 dispatch append: n=%d p50=%.2f ms p95=%.2f ms max=%.2f ms%n",
        samples, p50 / 1000.0, p95 / 1000.0, micros[samples - 1] / 1000.0);

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM studio_outbox", Integer.class))
        .as("every sample committed a row, so the measurement is of the real work")
        .isEqualTo(samples);
    // A validity bound, not a target: this asserts the measurement is of an insert and not of a
    // stall. A harness that reddens when its own number is unwelcome puts pressure on the number.
    assertThat(p95).as("p95 micros for one durable dispatch").isLessThan(100_000L);
  }

  private static StepDispatch dispatch() {
    return new StepDispatch(
        RUN,
        "describe",
        2,
        "orazaka.core.media.vision",
        null,
        Map.of("assetId", "a1"),
        "actor-1",
        "corr-1",
        Map.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        com.orazaka.jobs.domain.model.DataClass.STANDARD);
  }
}
