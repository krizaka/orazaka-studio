package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.studioservice.domain.exception.ContentPolicyRefusedException;
import com.krizaka.orazaka.studioservice.infrastructure.support.RulesContentClassifier;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The content policy against the <b>real</b> {@code 80-studio.sql}: the tables the operator
 * configures, the append-only audit, and a refusal's trace surviving the rollback of the run's own
 * transaction. Prompts are neutral markers expanded to ordinary classifier vocabulary.
 */
class ContentPolicyServiceIT {

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
  private static DataSourceTransactionManager tx;
  private static ContentPolicyService service;

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
    tx = new DataSourceTransactionManager(dataSource);
    service = new ContentPolicyService(jdbc, new RulesContentClassifier(), tx);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) {
      dataSource.close();
    }
    POSTGRES.stop();
  }

  @Test
  @DisplayName(
      "Fresh install is permissive; the operator's rows decide; the audit outlives a rollback")
  void operatorConfiguresAndAuditSurvivesRollback() {
    var inputs = Map.<String, Object>of("prompt", "a pornographic video");
    // No rows: permissive.
    assertThat(service.evaluate("a1", inputs, List.of("cap.video")).refused()).isFalse();

    // The operator marks the provider as not allowing adult content.
    jdbc.update(
        "INSERT INTO content_provider_policy (provider_key, allows_adult_content, note)"
            + " VALUES ('cap.video', FALSE, 'provider terms')");
    assertThat(service.evaluate("a1", inputs, List.of("cap.video")).rule())
        .isEqualTo("provider.cap.video.allows_adult_content");

    // A refusal inside the run's transaction: the transaction rolls back, the audit row stays.
    assertThatThrownBy(
            () ->
                new TransactionTemplate(tx)
                    .executeWithoutResult(
                        s -> service.enforce("a1", "video-studio", inputs, List.of("cap.video"))))
        .isInstanceOf(ContentPolicyRefusedException.class);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT decision, rule, labels FROM content_policy_audit WHERE actor_id = 'a1'"
                + " AND dry_run = FALSE");
    assertThat(row.get("decision")).isEqualTo("REFUSED");
    assertThat(row.get("labels").toString()).contains("SEXUAL_EXPLICIT");
  }

  @Test
  @DisplayName("The audit is append-only")
  void auditIsAppendOnly() {
    service.check("a2", "s", Map.of("prompt", "a pornographic video"), List.of());
    assertThatThrownBy(() -> jdbc.update("DELETE FROM content_policy_audit WHERE actor_id = 'a2'"))
        .hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("minors_risk is refused even when the tenant allows everything")
  void minorsAlwaysRefused() {
    jdbc.update(
        "INSERT INTO content_policy_tenant (subject, label, action)"
            + " VALUES ('*', 'SEXUAL_EXPLICIT', 'ALLOW')");
    var d =
        service.evaluate("a3", Map.of("prompt", "a 12 year old child, pornographic"), List.of());
    assertThat(d.refused()).isTrue();
    assertThat(d.rule()).isEqualTo("minors_risk.always");
  }
}
