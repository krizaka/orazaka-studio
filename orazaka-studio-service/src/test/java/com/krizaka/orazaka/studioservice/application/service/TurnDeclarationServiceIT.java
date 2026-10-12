package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.studio.domain.exception.TurnConsentRequiredException;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The declarations a conversational turn reads, against the <b>real</b> {@code 80-studio.sql}
 * (ADR-076): the pack row's JSONB, the installation's region and recorded consent, and the row
 * predicate that keeps one person's installation out of another's turn.
 */
class TurnDeclarationServiceIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440077";

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
  private static TurnDeclarationService service;

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
    service = new TurnDeclarationService(jdbc, new ColumnValueResolver(new ObjectMapper()));

    jdbc.update(
        "INSERT INTO pack_category (category_key, icon_key) VALUES ('lifestyle', 'heart')"
            + " ON CONFLICT DO NOTHING");
    jdbc.update(
        """
        INSERT INTO pack (pack_key, category_key, icon_key, regulatory_class, scope_guard,
                          consent_version, consent_statement, safety, status)
        VALUES ('bien-etre', 'lifestyle', 'sparkles', 'REGULATED',
                '{"refusedTerms":["posologie"],"refusal":"Je ne parle pas de posologie."}',
                '1.0', 'Ce Studio enregistre…',
                '{"crisisTerms":["en finir"],"response":"Je m''arrête là.","safetyReviewRef":"r",
                  "systemPrompt":"Je suis une présence IA.",
                  "resources":{"CA-QC":{"region":"CA-QC","label":"Prévention du suicide",
                    "contact":"1 866 APPELLE","availability":"24/7",
                    "sourceUrl":"https://www.quebec.ca/","verifiedOn":"2026-10-11"}}}',
                'PUBLISHED')
        """);
    jdbc.update(
        "INSERT INTO studio (studio_key, label, profession, icon_key, pricing, entitlement_key)"
            + " VALUES ('session-summary', 'Résumé', 'general', 'notebook', 'FREE',"
            + " 'studio.session-summary')");
    jdbc.update(
        "INSERT INTO pack_studio (pack_key, studio_key) VALUES ('bien-etre', 'session-summary')");
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) {
      dataSource.close();
    }
    POSTGRES.stop();
  }

  private static UUID install(String actor, String consentVersion, String status) {
    jdbc.update("DELETE FROM studio_installation WHERE actor_id = ?", actor);
    return jdbc.queryForObject(
        "INSERT INTO studio_installation (actor_id, studio_key, pinned_version, status,"
            + " consent_version, consent_recorded_at, region)"
            + " VALUES (?, 'session-summary', '1.0.0', ?, ?, now(), 'CA-QC') RETURNING id",
        UUID.class,
        actor,
        status,
        consentVersion);
  }

  @Test
  @DisplayName("An active installation answers its pack's guards, the region's line and the prompt")
  void readsTheDeclarations() {
    UUID id = install(ACTOR, "1.0", "ACTIVE");

    TurnDeclarations declared = service.forInstallation(id, ACTOR).orElseThrow();

    assertThat(declared.packKey()).isEqualTo("bien-etre");
    assertThat(declared.region()).isEqualTo("CA-QC");
    assertThat(declared.scopeGuard().refusedTerms()).containsExactly("posologie");
    assertThat(declared.crisisTerms()).containsExactly("en finir");
    assertThat(declared.crisisReply()).startsWith("Je m'arrête là.").contains("1 866 APPELLE");
    assertThat(declared.crisisResource().sourceUrl()).isEqualTo("https://www.quebec.ca/");
    assertThat(declared.systemPrompt()).isEqualTo("Je suis une présence IA.");
  }

  @Test
  @DisplayName("Somebody else's installation is no installation — the same answer as none")
  void scopedToItsOwner() {
    UUID id = install(ACTOR, "1.0", "ACTIVE");

    assertThat(service.forInstallation(id, "someone-else")).isEmpty();
    assertThat(service.forInstallation(UUID.randomUUID(), ACTOR)).isEmpty();
  }

  @Test
  @DisplayName("A revoked installation takes no turn")
  void revokedIsAbsent() {
    UUID id = install(ACTOR, "1.0", "REVOKED");

    assertThat(service.forInstallation(id, ACTOR)).isEmpty();
  }

  @Test
  @DisplayName("Consent to an earlier statement stops the turn, with the statement now in force")
  void staleConsentRefuses() {
    UUID id = install(ACTOR, "0.9", "ACTIVE");

    assertThatThrownBy(() -> service.forInstallation(id, ACTOR))
        .isInstanceOfSatisfying(
            TurnConsentRequiredException.class,
            refused -> {
              assertThat(refused.requiredVersion()).isEqualTo("1.0");
              assertThat(refused.statement()).isEqualTo("Ce Studio enregistre…");
            });
  }
}
