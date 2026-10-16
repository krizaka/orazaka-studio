package com.krizaka.orazaka.studioservice.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Cancelling a run tells the executors to stop (orazaka-web-client#85). */
class StudioRunCancelTest {

  private final OutboxService outbox = mock(OutboxService.class);
  private StudioRunService runs;

  @BeforeEach
  void wire() {
    runs =
        new StudioRunService(
            mock(JdbcTemplate.class),
            mock(StudioInstallationService.class),
            mock(BlueprintRepository.class),
            mock(CreditReservationService.class),
            mock(RunSettlementService.class),
            mock(RunSagaService.class),
            mock(StudioRuntimeConfigService.class),
            outbox,
            new ObjectMapper(),
            new ColumnValueResolver(new ObjectMapper()),
            mock(RunAuditService.class),
            mock(StudioCatalogService.class),
            mock(StudioAccessService.class),
            mock(ContentPolicyService.class));
  }

  @Test
  @DisplayName("every in-flight job gets a job.cancel.<id> command on the jobs exchange")
  void stopsEachInFlightJob() {
    UUID run = UUID.randomUUID();

    runs.publishStop(run, List.of("job-a", "job-b"));

    verify(outbox)
        .appendCommand(
            eq(run.toString()),
            eq("orazaka.jobs"),
            eq("job.cancel.job-a"),
            eq("job-a-cancel"),
            eq(Map.of("jobId", "job-a")));
    verify(outbox)
        .appendCommand(
            eq(run.toString()),
            eq("orazaka.jobs"),
            eq("job.cancel.job-b"),
            eq("job-b-cancel"),
            eq(Map.of("jobId", "job-b")));
  }

  @Test
  @DisplayName("a run with nothing in flight publishes nothing")
  void nothingToStop() {
    runs.publishStop(UUID.randomUUID(), List.of());

    verify(outbox, never()).appendCommand(any(), any(), any(), any(), any());
  }
}
