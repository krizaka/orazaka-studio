package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.model.EntitlementSnapshot;
import com.krizaka.billing.domain.port.EntitlementProvider;
import com.krizaka.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import com.krizaka.orazaka.studio.domain.model.StudioStatus;
import com.krizaka.orazaka.studioservice.application.service.StudioAccessService;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.RestErrorResolver;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.AbstractContainerIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The gate of the Contributor pack's Studios against a real database (ADR-093 §3,
 * krizaka/orazaka#450): the engine refuses the model turn of every run whose actor holds no
 * entitlement, and the only thing that can open it besides billing is an operator grant, a
 * <b>row</b> read from the studio schema (80-studio.sql) on every run, with its expiry judged by
 * the database's clock. There is no local flag to forge.
 *
 * <p>The containers are the shared ones of {@link AbstractContainerIntegrationTest}; this class
 * owns the {@code orazaka_studio_db} database it creates in them.
 */
class CodeContributorGateIT extends AbstractContainerIntegrationTest {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final String OTHER_ACTOR = "550e8400-e29b-41d4-a716-446655440003";
  private static final String PACK = "code-contributor";
  private static final String[] STUDIOS = {"code-issue-to-pr", "code-read-write"};

  private static HikariDataSource dataSource;
  private static JdbcTemplate jdbc;

  private final EntitlementProvider entitlements = mock(EntitlementProvider.class);
  private StudioAccessService access;

  @BeforeAll
  static void createStudioSchema() throws Exception {
    PostgreSQLContainer<?> postgres = postgres();
    postgres.copyFileToContainer(
        MountableFile.forHostPath(
            SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
                .resolve("80-studio.sql")),
        "/tmp/80-studio.sql");
    var created =
        postgres.execInContainer(
            "psql",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "-v",
            "ON_ERROR_STOP=1",
            "-f",
            "/tmp/80-studio.sql");
    assertThat(created.getExitCode()).as(created.getStderr()).isZero();
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(
        postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + STUDIO_DB));
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  @AfterAll
  static void close() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void cleanGrantsAndWire() {
    jdbc.update("DELETE FROM studio_entitlement_grant");
    access = new StudioAccessService(entitlements, new JdbcEntitlementGrantRepositoryAdapter(jdbc));
    when(entitlements.forActor(anyString()))
        .thenReturn(
            new EntitlementSnapshot(
                ACTOR, "premium", billed(), 5000, Instant.now().plusSeconds(60)));
  }

  private static Map<String, String> billed(String... keys) {
    Map<String, String> matrix = new HashMap<>();
    for (String key : keys) {
      matrix.put(key, "true");
    }
    return matrix;
  }

  private void billedFor(String... keys) {
    when(entitlements.forActor(anyString()))
        .thenReturn(
            new EntitlementSnapshot(
                ACTOR, "premium", billed(keys), 5000, Instant.now().plusSeconds(60)));
  }

  private void grant(String actor, String key, String expiresAt) {
    jdbc.update(
        "INSERT INTO studio_entitlement_grant (actor_id, entitlement_key, reason, granted_by, expires_at)"
            + " VALUES (?, ?, 'dogfooding', 'operator', ?::timestamptz)",
        actor,
        key,
        expiresAt);
  }

  private static Studio studio(String key) {
    return new Studio(
        key,
        "Label",
        null,
        "developer",
        "code",
        null,
        StudioPricing.PAID,
        PACK,
        PackKind.VERTICAL,
        "studio." + key,
        StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        Set.of(),
        Instant.parse("2026-10-27T10:00:00Z"));
  }

  @Test
  @DisplayName(
      "a forged run with no entitlement and no grant in the database is refused, and the wire says studio_not_entitled")
  void aForgedRunIsRefused() {
    billedFor();
    for (String key : STUDIOS) {
      assertThatThrownBy(() -> access.requireEntitled(studio(key), ACTOR))
          .isInstanceOf(StudioNotEntitledException.class);
    }
    ResponseEntity<Map<String, Object>> wire =
        new RestErrorResolver()
            .handleNotEntitled(
                new StudioNotEntitledException(
                    "code-issue-to-pr", "studio.code-issue-to-pr", PACK));
    assertThat(wire.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(wire.getBody()).containsEntry("status", "studio_not_entitled");
  }

  @Test
  @DisplayName(
      "a grant row opens exactly its Studio and its actor; deleting the row closes it on the next run")
  void aGrantRowOpensOnlyItsStudioAndActor() {
    billedFor();
    grant(ACTOR, "studio.code-issue-to-pr", null);

    assertThatCode(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> access.requireEntitled(studio("code-read-write"), ACTOR))
        .isInstanceOf(StudioNotEntitledException.class);
    assertThatThrownBy(() -> access.requireEntitled(studio("code-issue-to-pr"), OTHER_ACTOR))
        .isInstanceOf(StudioNotEntitledException.class);

    jdbc.update("DELETE FROM studio_entitlement_grant WHERE actor_id = ?", ACTOR);
    assertThatThrownBy(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .isInstanceOf(StudioNotEntitledException.class);
  }

  @Test
  @DisplayName("a pilot grant past its expiry is not in force: the database's clock decides")
  void anExpiredGrantIsClosed() {
    billedFor();
    grant(ACTOR, "studio.code-issue-to-pr", "2020-01-01T00:00:00Z");
    assertThatThrownBy(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .isInstanceOf(StudioNotEntitledException.class);

    jdbc.update("DELETE FROM studio_entitlement_grant");
    grant(ACTOR, "studio.code-issue-to-pr", "2999-01-01T00:00:00Z");
    assertThatCode(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("billing opens the Studio it sold, and an unrelated entitlement opens neither")
  void billingOpensOnlyWhatItSold() {
    billedFor("studio.code-read-write", "studio.trade-showcase");
    assertThatCode(() -> access.requireEntitled(studio("code-read-write"), ACTOR))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .isInstanceOf(StudioNotEntitledException.class);
  }

  @Test
  @DisplayName(
      "a billing outage does not open a PAID Studio, and a grant in the database still does")
  void anOutageDoesNotOpenItButAGrantDoes() {
    when(entitlements.forActor(anyString()))
        .thenReturn(EntitlementSnapshot.unresolved(ACTOR, Instant.now().plusSeconds(5)));
    assertThatThrownBy(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .isInstanceOf(StudioNotEntitledException.class);

    grant(ACTOR, "studio.code-issue-to-pr", null);
    assertThatCode(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .doesNotThrowAnyException();
  }
}
