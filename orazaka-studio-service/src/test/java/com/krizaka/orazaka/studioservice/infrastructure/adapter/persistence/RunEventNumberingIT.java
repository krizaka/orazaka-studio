package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * The numbering of a run's changes against the <b>real</b> {@code 80-studio.sql} (orazaka#241): the
 * database raises {@code studio_run.event_seq} at each change of a run or one of its steps,
 * whichever code writes, and keeps what each number was.
 */
class RunEventNumberingIT {

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
  private static JdbcRunEventLogAdapter log;

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
    log = new JdbcRunEventLogAdapter(jdbc);
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
    jdbc.update("DELETE FROM studio_run");
  }

  private UUID run(String actor) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO studio_run (id, actor_id, studio_key, blueprint_version, status,"
            + " correlation_id) VALUES (?, ?, 'image', '1.0.0', 'RUNNING', ?)",
        id,
        actor,
        "corr-" + id);
    return id;
  }

  private long seq(UUID run) {
    return jdbc.queryForObject("SELECT event_seq FROM studio_run WHERE id = ?", Long.class, run);
  }

  @Test
  @DisplayName(
      "A run is number 1 when created, and each change of its status, outputs, error or end raises"
          + " it by one")
  void runChangesAreNumbered() {
    UUID run = run("a1");
    assertThat(seq(run)).isEqualTo(1);

    jdbc.update("UPDATE studio_run SET status = 'AWAITING_INPUT' WHERE id = ?", run);
    assertThat(seq(run)).isEqualTo(2);
    jdbc.update("UPDATE studio_run SET outputs = '{\"a\":1}'::jsonb WHERE id = ?", run);
    assertThat(seq(run)).isEqualTo(3);
    jdbc.update(
        "UPDATE studio_run SET status = 'FAILED', error_message = 'boom', finished_at = now() WHERE"
            + " id = ?",
        run);
    assertThat(seq(run)).as("one statement, one number").isEqualTo(4);

    jdbc.update("UPDATE studio_run SET hold_id = 'h1' WHERE id = ?", run);
    assertThat(seq(run)).as("a change nobody sees is not an event").isEqualTo(4);
  }

  @Test
  @DisplayName("A step that is added, or whose status or output changes, is a change of its run")
  void stepChangesAreNumbered() {
    UUID run = run("a1");
    jdbc.update(
        "INSERT INTO studio_run_step (run_id, step_id, status) VALUES (?, 's1', 'PENDING')", run);
    assertThat(seq(run)).isEqualTo(2);
    jdbc.update("UPDATE studio_run_step SET status = 'RUNNING' WHERE run_id = ?", run);
    assertThat(seq(run)).isEqualTo(3);
    jdbc.update("UPDATE studio_run_step SET attempts = attempts + 1 WHERE run_id = ?", run);
    assertThat(seq(run)).as("an attempt counter is not worth an event").isEqualTo(3);
    jdbc.update("UPDATE studio_run_step SET output = '{\"x\":1}'::jsonb WHERE run_id = ?", run);
    assertThat(seq(run)).isEqualTo(4);
  }

  @Test
  @DisplayName("Each number is kept with what the run was, gapless, and goes with the run")
  void theLogIsGaplessAndGoesWithTheRun() {
    UUID run = run("a1");
    jdbc.update(
        "UPDATE studio_run SET status = 'SUCCEEDED', finished_at = now() WHERE id = ?", run);

    List<Map<String, Object>> events =
        jdbc.queryForList(
            "SELECT seq, kind, detail->>'status' AS status FROM studio_run_event WHERE run_id = ?"
                + " ORDER BY seq",
            run);
    assertThat(events).extracting(e -> e.get("seq")).containsExactly(1L, 2L);
    assertThat(events).extracting(e -> e.get("kind")).containsExactly("run.created", "run.updated");
    assertThat(events).extracting(e -> e.get("status")).containsExactly("RUNNING", "SUCCEEDED");

    jdbc.update("DELETE FROM studio_run WHERE id = ?", run);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM studio_run_event", Integer.class))
        .isZero();
  }

  @Test
  @DisplayName("The adapter reads the actor's own run number and nothing of another actor's")
  void adapterIsScopedToTheActor() {
    UUID run = run("a1");
    jdbc.update("UPDATE studio_run SET status = 'AWAITING_INPUT' WHERE id = ?", run);

    assertThat(log.currentSeq(run, "a1")).hasValue(2);
    assertThat(log.currentSeq(run, "a2")).isEmpty();
    assertThat(log.currentSeq(UUID.randomUUID(), "a1")).isEmpty();
  }
}
