package com.orazaka.studioservice.infrastructure.adapter.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orazaka.studioservice.application.service.StudioRuntimeConfigService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RunSweeperTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final com.orazaka.studioservice.application.service.RunSettlementService
      settlementService =
          mock(com.orazaka.studioservice.application.service.RunSettlementService.class);
  private final StudioRuntimeConfigService runtimeConfigService =
      mock(StudioRuntimeConfigService.class);
  private final com.orazaka.studioservice.application.service.RunAuditService runAuditService =
      mock(com.orazaka.studioservice.application.service.RunAuditService.class);
  private final RunSweeper sweeper =
      new RunSweeper(jdbcTemplate, settlementService, runtimeConfigService, runAuditService);

  @Test
  @DisplayName("A stalled run is failed AND its hold released — releasing is the whole point")
  void releasesTheHoldOfAStalledRun() {
    when(runtimeConfigService.intValue(anyString(), anyInt())).thenReturn(900);
    java.util.UUID runId = java.util.UUID.randomUUID();
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyInt()))
        .thenAnswer(invocation -> List.of(stale(runId, "BATCH")));

    sweeper.sweep();

    verify(settlementService).releaseAbandoned(eq("hold-7"), anyString());
  }

  @Test
  @DisplayName("Nothing stalled means nothing released")
  void quietWhenNothingIsStalled() {
    when(runtimeConfigService.intValue(anyString(), anyInt())).thenReturn(900);
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyInt()))
        .thenReturn(List.of());

    sweeper.sweep();

    verify(settlementService, never()).releaseAbandoned(anyString(), anyString());
  }

  @Test
  @DisplayName("Each lane's ceiling comes from its own row — both must be tunable at 2am")
  void eachLaneHasItsOwnTunableCeiling() {
    // ADR-067: one number could not serve both. The query carries BOTH, and the CASE in it picks
    // per step, so a batch step queued behind a 67-second image is judged by the batch ceiling
    // while a hung text step is reaped at the interactive one.
    when(runtimeConfigService.intValue("run.step-timeout-seconds.interactive", 300))
        .thenReturn(120);
    when(runtimeConfigService.intValue("run.step-timeout-seconds.batch", 1800)).thenReturn(3600);
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyInt()))
        .thenReturn(List.of());

    sweeper.sweep();

    verify(jdbcTemplate)
        .query(
            anyString(),
            any(RowMapper.class),
            anyString(),
            anyString(),
            anyString(),
            eq(120),
            eq(3600));
  }

  @Test
  @DisplayName("The lane a step was dispatched in names the ceiling it is judged by")
  void theLaneNamesTheCeilingInTheFailure() throws Exception {
    when(runtimeConfigService.intValue("run.step-timeout-seconds.interactive", 300))
        .thenReturn(300);
    when(runtimeConfigService.intValue("run.step-timeout-seconds.batch", 1800)).thenReturn(1800);
    java.util.UUID runId = java.util.UUID.randomUUID();
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyInt()))
        .thenAnswer(invocation -> List.of(stale(runId, "INTERACTIVE")));

    sweeper.sweep();

    org.mockito.ArgumentCaptor<String> reason = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE studio_run SET status"),
            anyString(),
            reason.capture(),
            anyString(),
            eq(runId));
    assertThat(reason.getValue()).contains("INTERACTIVE").contains("300s");
  }

  /** Builds the private StaleRun the query maps to, via the sweeper's own row mapper. */
  private static Object stale(java.util.UUID runId, String lane) throws Exception {
    Class<?> type =
        Class.forName(
            "com.orazaka.studioservice.infrastructure.adapter.schedule.RunSweeper$StaleRun");
    var constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(runId, "hold-7", "actor-7", "compliance-check", lane);
  }

  @Test
  @DisplayName("[ADR-053] a swept run declares TIMEOUT and appears in the protected trail")
  void declaresItsCauseAndWritesTheTrail() {
    when(runtimeConfigService.intValue(anyString(), anyInt())).thenReturn(900);
    java.util.UUID runId = java.util.UUID.randomUUID();
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyInt()))
        .thenAnswer(
            invocation -> {
              RowMapper<?> mapper = invocation.getArgument(1);
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.getObject("id", java.util.UUID.class)).thenReturn(runId);
              when(rs.getString("hold_id")).thenReturn("hold-7");
              when(rs.getString("actor_id")).thenReturn("actor-7");
              when(rs.getString("studio_key")).thenReturn("compliance-check");
              when(rs.getString("lane")).thenReturn("BATCH");
              return java.util.List.of(mapper.mapRow(rs, 0));
            });

    sweeper.sweep();

    // Before this, a swept SENSITIVE run left NO audit row: the one terminal path that recorded
    // nothing, which is the hole a trail cannot have.
    org.mockito.Mockito.verify(runAuditService)
        .recordFinished(
            eq(runId),
            eq("actor-7"),
            eq("compliance-check"),
            eq(false),
            eq(com.orazaka.jobs.domain.model.FailureCause.TIMEOUT));
  }
}
