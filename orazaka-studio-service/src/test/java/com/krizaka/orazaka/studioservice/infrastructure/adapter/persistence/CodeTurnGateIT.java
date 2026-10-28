package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.model.EntitlementSnapshot;
import com.krizaka.billing.domain.port.EntitlementProvider;
import com.krizaka.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import com.krizaka.orazaka.studio.domain.model.StudioStatus;
import com.krizaka.orazaka.studioservice.application.service.CodeTurnService;
import com.krizaka.orazaka.studioservice.application.service.StudioAccessService;
import com.krizaka.orazaka.studioservice.application.service.StudioCatalogService;
import com.krizaka.orazaka.studioservice.application.service.StudioRunService;
import com.krizaka.orazaka.studioservice.domain.model.AgentTurn;
import com.krizaka.orazaka.studioservice.domain.model.AgentTurnResult;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.studioservice.domain.port.AgentTurnClient;
import com.krizaka.test.container.AbstractContainerIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The per-turn pack check of the code agent against a real database (ADR-093 §3, §6,
 * krizaka/orazaka#450): every model turn re-reads the operator grants (80-studio.sql), so a grant
 * revoked ends the session at its next turn, and a forged call from an actor who never held the
 * pack never reaches the engine.
 *
 * <p>The containers are the shared ones of {@link AbstractContainerIntegrationTest}.
 */
class CodeTurnGateIT extends AbstractContainerIntegrationTest {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440012";
  private static final UUID RUN = UUID.fromString("550e8400-e29b-41d4-a716-446655440099");
  private static final String KEY = "code-issue-to-pr";

  private static HikariDataSource dataSource;
  private static JdbcTemplate jdbc;

  private final EntitlementProvider entitlements = mock(EntitlementProvider.class);
  private final AgentTurnClient engine = mock(AgentTurnClient.class);
  private CodeTurnService service;

  @BeforeAll
  static void createStudioSchema() throws Exception {
    PostgreSQLContainer<?> postgres = postgres();
    SharedStudioSchema.ensureLoaded(postgres);
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
  void wire() {
    jdbc.update("DELETE FROM studio_entitlement_grant");
    when(entitlements.forActor(anyString()))
        .thenReturn(
            new EntitlementSnapshot(
                ACTOR, "premium", new HashMap<>(), 5000, Instant.now().plusSeconds(60)));
    StudioRunService runs = mock(StudioRunService.class);
    when(runs.find(RUN, ACTOR))
        .thenReturn(
            Optional.of(
                new RunDetail(
                    RUN,
                    null,
                    KEY,
                    "1.0.0",
                    RunStatus.RUNNING,
                    null,
                    null,
                    null,
                    Instant.now(),
                    null,
                    null,
                    null,
                    null)));
    StudioCatalogService catalog = mock(StudioCatalogService.class);
    when(catalog.find(KEY, null)).thenReturn(Optional.of(studio()));
    when(engine.turn(any())).thenReturn(new AgentTurnResult("done", List.of(), 1, 1, 0));
    service =
        new CodeTurnService(
            runs,
            catalog,
            new StudioAccessService(entitlements, new JdbcEntitlementGrantRepositoryAdapter(jdbc)),
            engine);
  }

  private static Studio studio() {
    return new Studio(
        KEY,
        "Label",
        null,
        "developer",
        "code",
        null,
        StudioPricing.PAID,
        "code-contributor",
        PackKind.VERTICAL,
        "studio." + KEY,
        StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        Set.of(),
        Instant.parse("2026-10-27T10:00:00Z"));
  }

  private AgentTurnResult turn() {
    return service.turn(
        RUN,
        ACTOR,
        "fixed",
        List.of(new AgentTurn.Message("user", "go", null, null)),
        List.of(new AgentTurn.Tool("read_file", "reads", "{}")));
  }

  private void grant() {
    jdbc.update(
        "INSERT INTO studio_entitlement_grant (actor_id, entitlement_key, reason, granted_by)"
            + " VALUES (?, ?, 'dogfooding', 'operator')",
        ACTOR,
        "studio." + KEY);
  }

  @Test
  @DisplayName(
      "a forged turn with no entitlement and no grant is refused before the engine is asked")
  void aForgedTurnIsRefused() {
    assertThatThrownBy(this::turn).isInstanceOf(StudioNotEntitledException.class);
    verify(engine, never()).turn(any());
  }

  @Test
  @DisplayName(
      "with a grant the turn reaches the engine; revoking the grant refuses the very next turn")
  void aRevokedGrantEndsTheSessionAtItsNextTurn() {
    grant();
    assertThat(turn().text()).isEqualTo("done");

    jdbc.update("DELETE FROM studio_entitlement_grant WHERE actor_id = ?", ACTOR);
    assertThatThrownBy(this::turn).isInstanceOf(StudioNotEntitledException.class);
    verify(engine, times(1)).turn(any());
  }
}
