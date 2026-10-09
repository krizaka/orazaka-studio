package com.krizaka.orazaka.studioservice.infrastructure.adapter.amqp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.messaging.dedup.MessageDedup;
import com.krizaka.orazaka.studioservice.application.service.RunSagaService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobOutcomeListenerTest {

  private final RunSagaService runSagaService = mock(RunSagaService.class);
  private final MessageDedup dedupService = mock(MessageDedup.class);
  private final JobOutcomeListener listener = new JobOutcomeListener(runSagaService, dedupService);

  @Test
  @DisplayName("A first delivery advances the DAG")
  void firstDeliveryAdvances() {
    when(dedupService.claim(anyString(), anyString())).thenReturn(true);

    listener.onJobOutcome(
        new JobOutcomeEvent("job-1", Map.of("assetId", "a1"), null, null, null, null), "msg-1");

    verify(runSagaService).applyOutcome(eq("job-1"), any(), any(), isNull(), isNull(), any());
  }

  @Test
  @DisplayName("An outcome that fails to apply gives its claim back — a run must not be stranded")
  void releasesTheClaimWhenTheOutcomeCannotBeApplied() {
    // audit #30 on the saga's side: a claim kept after a failure leaves the run non-terminal and
    // its hold outstanding, and the redelivery that would have fixed it is refused by the claim.
    when(dedupService.claim(anyString(), anyString())).thenReturn(true);
    org.mockito.Mockito.doThrow(new IllegalStateException("the studio database was unreachable"))
        .when(runSagaService)
        .applyOutcome(anyString(), any(), any(), any(), any(), any());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                listener.onJobOutcome(
                    new JobOutcomeEvent("job-1", Map.of(), null, null, null, null), "msg-1"))
        .isInstanceOf(IllegalStateException.class);

    verify(dedupService).release("studio-saga", "msg-1");
  }

  @Test
  @DisplayName("A redelivery is dropped before it can re-submit a paid job")
  void redeliveryIsDropped() {
    when(dedupService.claim(anyString(), anyString())).thenReturn(false);

    listener.onJobOutcome(new JobOutcomeEvent("job-1", Map.of(), null, null, null, null), "msg-1");

    verify(runSagaService, never()).applyOutcome(anyString(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("A failure carries its error through to the saga")
  void failureCarriesTheError() {
    when(dedupService.claim(anyString(), anyString())).thenReturn(true);

    listener.onJobOutcome(
        new JobOutcomeEvent("job-1", null, null, null, "MLX out of memory", null), "msg-2");

    verify(runSagaService)
        .applyOutcome(eq("job-1"), any(), any(), isNull(), eq("MLX out of memory"), any());
  }

  @Test
  @DisplayName("A malformed event is ignored rather than dead-lettering the queue")
  void malformedEventIgnored() {
    listener.onJobOutcome(null, "msg-3");
    listener.onJobOutcome(new JobOutcomeEvent(null, Map.of(), null, null, null, null), "msg-4");

    verify(runSagaService, never()).applyOutcome(anyString(), any(), any(), any(), any(), any());
  }
}
