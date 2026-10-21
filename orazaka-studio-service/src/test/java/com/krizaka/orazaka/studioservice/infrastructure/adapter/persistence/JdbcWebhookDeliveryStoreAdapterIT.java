package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider;
import com.krizaka.orazaka.studioservice.domain.model.DueWebhook;
import com.krizaka.orazaka.studioservice.domain.model.WebhookEndpoint;
import com.krizaka.orazaka.studioservice.domain.policy.WebhookSignature;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
  private static MasterKeyWebhookSecretSealer sealer;
  private static WebhookSecretMigration migration;

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
    try {
      Path keyring = Files.createTempDirectory("keyring").resolve("master.key");
      FileMasterKeyProvider.addKey(keyring, "k1");
      sealer = new MasterKeyWebhookSecretSealer(new FileMasterKeyProvider(keyring));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    store = new JdbcWebhookDeliveryStoreAdapter(jdbc, sealer);
    migration =
        new WebhookSecretMigration(jdbc, new DataSourceTransactionManager(dataSource), sealer);
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
            + " VALUES (?, ?, 'https://hooks.example.com/x', ?, ?,"
            + " now() - interval '1 hour')",
        id,
        actor,
        sealer.seal(id, "whsec_current"),
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
    assertThat(first.getFirst().payload()).contains("\"run.completed\"").contains(run.toString());
    assertThat(first.getFirst().secret()).isEqualTo("whsec_current");
    assertThat(store.claimDue(10, Duration.ofSeconds(60))).as("leased").isEmpty();

    UUID delivery = first.getFirst().id();
    store.retryLater(delivery, 503, "the receiver answered 503", Duration.ZERO);
    List<DueWebhook> again = store.claimDue(10, Duration.ofSeconds(60));
    assertThat(again).hasSize(1);
    assertThat(again.getFirst().attempts()).isEqualTo(1);

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
    UUID delivery = store.claimDue(10, Duration.ZERO).getFirst().id();

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
        "UPDATE studio_webhook_endpoint SET previous_secret = ?,"
            + " previous_secret_until = now() + interval '1 hour' WHERE id = ?",
        sealer.seal(endpoint, "whsec_old"),
        endpoint);
    finishedRun("a1", "SUCCEEDED");
    store.enqueueFinishedRuns();
    assertThat(store.claimDue(10, Duration.ZERO).getFirst().previousSecret())
        .isEqualTo("whsec_old");

    jdbc.update("UPDATE studio_webhook_delivery SET next_attempt_at = now()");
    jdbc.update(
        "UPDATE studio_webhook_endpoint SET previous_secret_until = now() - interval '1 minute'");
    assertThat(store.claimDue(10, Duration.ZERO).getFirst().previousSecret()).isNull();
  }

  @Test
  @DisplayName("The endpoint repository is scoped to its actor: create, list, rotate, log, delete")
  void endpointRepositoryIsScopedToTheActor() {
    var repository = new JdbcWebhookEndpointRepositoryAdapter(jdbc, sealer);
    var mine =
        repository.create(
            "a1", "https://hooks.example.com/mine", List.of("run.completed"), "whsec_one");
    repository.create("a2", "https://hooks.example.com/theirs", List.of("run.failed"), "whsec_two");

    assertThat(repository.count("a1")).isEqualTo(1);
    assertThat(repository.list("a1"))
        .extracting(WebhookEndpoint::url)
        .containsExactly("https://hooks.example.com/mine");
    assertThat(repository.find("a2", mine.id())).as("another actor's id").isEmpty();
    assertThat(repository.rotate("a2", mine.id(), "whsec_x", Duration.ofHours(1))).isFalse();
    assertThat(repository.rotate("a1", mine.id(), "whsec_three", Duration.ofHours(1))).isTrue();
    // Rotation keeps working: both secrets open, the old one is the previous, the new one current.
    finishedRun("a1", "SUCCEEDED");
    jdbc.update("UPDATE studio_webhook_endpoint SET created_at = now() - interval '1 hour'");
    store.enqueueFinishedRuns();
    DueWebhook rotated = store.claimDue(10, Duration.ZERO).getFirst();
    assertThat(rotated.secret()).isEqualTo("whsec_three");
    assertThat(rotated.previousSecret()).isEqualTo("whsec_one");
    jdbc.update("DELETE FROM studio_webhook_delivery");
    jdbc.update("DELETE FROM studio_run");

    finishedRun("a1", "SUCCEEDED");
    jdbc.update("UPDATE studio_webhook_endpoint SET created_at = now() - interval '1 hour'");
    store.enqueueFinishedRuns();
    assertThat(repository.deliveries("a1", mine.id(), 10)).hasSize(1);
    assertThat(repository.deliveries("a2", mine.id(), 10)).isEmpty();

    assertThat(repository.delete("a2", mine.id())).isFalse();
    assertThat(repository.delete("a1", mine.id())).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM studio_webhook_delivery", Integer.class))
        .as("the log goes with it")
        .isZero();
  }

  @Test
  @DisplayName("The secret column holds ciphertext, never the secret, on create and on rotation")
  void columnHoldsCiphertext() {
    var repository = new JdbcWebhookEndpointRepositoryAdapter(jdbc, sealer);
    var created =
        repository.create(
            "a1", "https://hooks.example.com/x", List.of("run.completed"), "whsec_clear_one");
    assertThat(
            jdbc.queryForObject(
                "SELECT secret FROM studio_webhook_endpoint WHERE id = ?",
                String.class,
                created.id()))
        .startsWith("v1.")
        .doesNotContain("whsec_clear_one");

    repository.rotate("a1", created.id(), "whsec_clear_two", Duration.ofHours(1));
    var row =
        jdbc.queryForMap(
            "SELECT secret, previous_secret FROM studio_webhook_endpoint WHERE id = ?",
            created.id());
    assertThat(row.values())
        .allSatisfy(
            value ->
                assertThat((String) value)
                    .startsWith("v1.")
                    .doesNotContain("whsec_clear_one")
                    .doesNotContain("whsec_clear_two"));
  }

  @Test
  @DisplayName("A delivery is still signed with the real secret, through ciphertext at rest")
  void sendingStillSignsCorrectly() {
    var repository = new JdbcWebhookEndpointRepositoryAdapter(jdbc, sealer);
    var created =
        repository.create(
            "a1", "https://hooks.example.com/x", List.of("run.completed"), "whsec_sign_me");
    finishedRun("a1", "SUCCEEDED");
    jdbc.update("UPDATE studio_webhook_endpoint SET created_at = now() - interval '1 hour'");
    store.enqueueFinishedRuns();
    DueWebhook due = store.claimDue(10, Duration.ZERO).getFirst();

    var headers =
        WebhookSignature.headers(
            due.secret(), due.previousSecret(), "msg_1", 1_700_000_000L, due.payload());

    assertThat(headers.get(WebhookSignature.SIGNATURE))
        .isEqualTo(WebhookSignature.sign("whsec_sign_me", "msg_1", 1_700_000_000L, due.payload()));
    assertThat(created.id()).isNotNull();
  }

  @Test
  @DisplayName("A secret sealed for one endpoint does not open for another")
  void aSealedSecretIsBoundToItsEndpoint() {
    String sealed = sealer.seal(UUID.randomUUID(), "whsec_x");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sealer.open(UUID.randomUUID(), sealed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("whsec_x");
  }

  @Test
  @DisplayName(
      "The migration seals the rows stored in the clear, keeps their value, and is idempotent")
  void migrationConvertsExistingRows() {
    // A row as the previous version stored it: a clear secret, a clear previous secret.
    jdbc.execute("ALTER TABLE studio_webhook_endpoint ALTER COLUMN secret TYPE VARCHAR(120)");
    jdbc.execute(
        "ALTER TABLE studio_webhook_endpoint ALTER COLUMN previous_secret TYPE VARCHAR(120)");
    UUID clear = UUID.randomUUID();
    UUID alreadySealed = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO studio_webhook_endpoint (id, actor_id, url, secret, previous_secret,"
            + " previous_secret_until) VALUES (?, 'a1', 'https://hooks.example.com/x',"
            + " 'whsec_legacy', 'whsec_legacy_old', now() + interval '1 hour')",
        clear);
    assertThat(migration.run()).isEqualTo(1);

    var row =
        jdbc.queryForMap(
            "SELECT secret, previous_secret FROM studio_webhook_endpoint WHERE id = ?", clear);
    assertThat(sealer.open(clear, (String) row.get("secret"))).isEqualTo("whsec_legacy");
    assertThat(sealer.open(clear, (String) row.get("previous_secret")))
        .isEqualTo("whsec_legacy_old");
    assertThat(row.values()).allSatisfy(v -> assertThat((String) v).doesNotContain("whsec_"));
    String sealedBefore = sealer.seal(alreadySealed, "whsec_new");
    jdbc.update(
        "INSERT INTO studio_webhook_endpoint (id, actor_id, url, secret) VALUES (?, 'a2',"
            + " 'https://hooks.example.com/y', ?)",
        alreadySealed,
        sealedBefore);
    assertThat(migration.run()).as("the second run converts nothing").isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT secret FROM studio_webhook_endpoint WHERE id = ?",
                String.class,
                alreadySealed))
        .as("a sealed row is left untouched")
        .isEqualTo(sealedBefore);
  }
}
