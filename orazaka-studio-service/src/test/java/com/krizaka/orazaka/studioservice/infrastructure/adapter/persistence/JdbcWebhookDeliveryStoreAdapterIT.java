package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.studioservice.domain.model.DueWebhook;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The webhook queue against the <b>real</b> {@code 80-studio.sql}: a finished run is queued once
 * per endpoint, a claim leases, a failure schedules the next attempt, and a rotated-out secret is
 * honoured only until its date (orazaka#189 slice B).
 */
class JdbcWebhookDeliveryStoreAdapterIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";

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

  private static HikariDataSource dataSource;
  private static JdbcTemplate jdbc;
  private static JdbcWebhookDeliveryStoreAdapter store;

  @BeforeAll
  static void start() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + STUDIO_DB);
    dataSource.setUsername(STUDIO_ROLE);
    dataSource.setPassword(STUDIO_PASSWORD);
    jdbc = new JdbcTemplate(dataSource);
    store = new JdbcWebhookDeliveryStoreAdapter(jdbc);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) {
      dataSource.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM studio_webhook_delivery");
    jdbc.update("DELETE FROM studio_webhook_endpoint");
    jdbc.update("DELETE FROM studio_run");
  }

  private UUID endpoint(String actor, String events) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO studio_webhook_endpoint (id, actor_id, url, secret, events, created_at)"
            + " VALUES (?, ?, 'https://hooks.example.com/x', 'whsec_current', ?,"
            + " now() - interval '1 hour')",
        id,
        actor,
        events);
    return id;
  }

  private UUID finishedRun(String actor, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO studio_run (id, actor_id, studio_key, blueprint_version, status,"
            + " correlation_id, finished_at) VALUES (?, ?, 'image', '1.0.0', ?, ?, now())",
        id,
        actor,
        status,
        "corr-" + id);
    return id;
  }

  @Test
  @DisplayName("A finished run is queued once per subscribed endpoint of its actor, and only once")
  void queuesOncePerEndpointAndEvent() {
    endpoint("a1", "run.completed,run.failed");
    endpoint("a2", "run.completed");
    finishedRun("a1", "SUCCEEDED");
    finishedRun("a1", "FAILED");
    finishedRun("a1", "RUNNING"); // no event for a status that is not final

    assertThat(store.enqueueFinishedRuns()).isEqualTo(2);
    assertThat(store.enqueueFinishedRuns()).as("the second sweep queues nothing").isZero();
    assertThat(
            jdbc.queryForList("SELECT DISTINCT event FROM studio_webhook_delivery", String.class))
        .containsExactlyInAnyOrder("run.completed", "run.failed");
  }

  @Test
  @DisplayName("An endpoint that does not subscribe to an event, or is disabled, gets nothing")
  void respectsSubscriptionAndEnabled() {
    UUID failedOnly = endpoint("a1", "run.failed");
    UUID off = endpoint("a1", "run.completed");
    jdbc.update("UPDATE studio_webhook_endpoint SET enabled = FALSE WHERE id = ?", off);
    finishedRun("a1", "SUCCEEDED");

    assertThat(store.enqueueFinishedRuns()).isZero();
    assertThat(failedOnly).isNotNull();
  }

  @Test
  @DisplayName("A run that finished before the endpoint existed is not announced")
  void ignoresRunsBeforeTheEndpoint() {
    finishedRun("a1", "SUCCEEDED");
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO studio_webhook_endpoint (id, actor_id, url, secret) VALUES (?, 'a1',"
            + " 'https://hooks.example.com/x', 'whsec_current')",
        id);
    jdbc.update("UPDATE studio_webhook_endpoint SET created_at = now() + interval '1 minute'");

    assertThat(store.enqueueFinishedRuns()).isZero();
  }

  @Test
  @DisplayName("A claim leases the delivery; success, retry and death are recorded")
  void claimLeasesAndOutcomesAreRecorded() {
    endpoint("a1", "run.completed");
    UUID run = finishedRun("a1", "SUCCEEDED");
    store.enqueueFinishedRuns();

    List<DueWebhook> first = store.claimDue(10, Duration.ofSeconds(60));
    assertThat(first).hasSize(1);
    assertThat(first.get(0).payload()).contains("\"run.completed\"").contains(run.toString());
    assertThat(first.get(0).secret()).isEqualTo("whsec_current");
    assertThat(store.claimDue(10, Duration.ofSeconds(60))).as("leased").isEmpty();

    UUID delivery = first.get(0).id();
    store.retryLater(delivery, 503, "the receiver answered 503", Duration.ZERO);
    List<DueWebhook> again = store.claimDue(10, Duration.ofSeconds(60));
    assertThat(again).hasSize(1);
    assertThat(again.get(0).attempts()).isEqualTo(1);

    store.delivered(delivery, 200);
    assertThat(store.claimDue(10, Duration.ZERO)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT status || ':' || attempts FROM studio_webhook_delivery WHERE id = ?",
                String.class,
                delivery))
        .isEqualTo("DELIVERED:2");
  }

  @Test
  @DisplayName("A dead delivery stays in the log and is never claimed again")
  void deadStaysInTheLog() {
    endpoint("a1", "run.failed");
    finishedRun("a1", "FAILED");
    store.enqueueFinishedRuns();
    UUID delivery = store.claimDue(10, Duration.ZERO).get(0).id();

    store.dead(delivery, 500, "the receiver answered 500");

    assertThat(store.claimDue(10, Duration.ZERO)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM studio_webhook_delivery WHERE id = ?", String.class, delivery))
        .isEqualTo("DEAD");
  }

  @Test
  @DisplayName("The previous secret is handed over only until its date")
  void previousSecretHonouredUntilItsDate() {
    UUID endpoint = endpoint("a1", "run.completed");
    jdbc.update(
        "UPDATE studio_webhook_endpoint SET previous_secret = 'whsec_old',"
            + " previous_secret_until = now() + interval '1 hour' WHERE id = ?",
        endpoint);
    finishedRun("a1", "SUCCEEDED");
    store.enqueueFinishedRuns();
    assertThat(store.claimDue(10, Duration.ZERO).get(0).previousSecret()).isEqualTo("whsec_old");

    jdbc.update("UPDATE studio_webhook_delivery SET next_attempt_at = now()");
    jdbc.update(
        "UPDATE studio_webhook_endpoint SET previous_secret_until = now() - interval '1 minute'");
    assertThat(store.claimDue(10, Duration.ZERO).get(0).previousSecret()).isNull();
  }
}
