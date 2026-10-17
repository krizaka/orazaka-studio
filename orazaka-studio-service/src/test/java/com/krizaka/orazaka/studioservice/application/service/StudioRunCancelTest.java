package com.krizaka.orazaka.studioservice.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Cancelling a run tells the executors to stop (orazaka-web-client#85). */
class StudioRunCancelTest {

  private final RunSagaService saga = mock(RunSagaService.class);
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
            saga,
            mock(StudioRuntimeConfigService.class),
            mock(OutboxService.class),
            new ObjectMapper(),
            new ColumnValueResolver(new ObjectMapper()),
            mock(RunAuditService.class),
            mock(StudioCatalogService.class),
            mock(StudioAccessService.class),
            mock(ContentPolicyService.class),
            mock(FormSchemaService.class));
  }

  @Test
  @DisplayName("every in-flight job is told to stop through the run's dispatch seam")
  void stopsEachInFlightJob() {
    UUID run = UUID.randomUUID();

    runs.publishStop(run, List.of("job-a", "job-b"));

    verify(saga).stop(run, "job-a");
    verify(saga).stop(run, "job-b");
  }

  @Test
  @DisplayName("a run with nothing in flight stops nothing")
  void nothingToStop() {
    runs.publishStop(UUID.randomUUID(), List.of());

    verify(saga, never()).stop(any(), any());
  }
}
