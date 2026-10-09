package com.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.ConsumptionReport;
import com.krizaka.billing.domain.model.CreditHoldCommand;
import com.krizaka.billing.domain.model.CreditHoldResponse;
import com.krizaka.billing.domain.model.MeteredStep;
import com.krizaka.billing.domain.port.CreditAuthorizationClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CreditReservationServiceTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  private final CreditAuthorizationClient creditClient = mock(CreditAuthorizationClient.class);
  private final CreditReservationService reservationService =
      new CreditReservationService(creditClient);

  @Test
  @DisplayName("A run takes ONE hold for the blueprint's estimate, not one per step")
  void holdsOncePerRun() {
    when(creditClient.hold(any()))
        .thenReturn(new CreditHoldResponse("hold-7", true, false, 80, 4920, 1));

    String holdId = reservationService.hold(ACTOR, "corr-1", 80);

    ArgumentCaptor<CreditHoldCommand> command = ArgumentCaptor.forClass(CreditHoldCommand.class);
    verify(creditClient).hold(command.capture());
    assertThat(holdId).isEqualTo("hold-7");
    assertThat(command.getValue().estimatedCredits()).isEqualTo(80);
    assertThat(command.getValue().correlationId()).isEqualTo("corr-1");
  }

  @Test
  @DisplayName("A failed run releases, never settles — a failed generation is not billed")
  void releaseOnFailure() {
    reservationService.release("hold-7", "step failed");

    verify(creditClient).release("hold-7", "step failed");
  }

  @Test
  @DisplayName("An unmetered run closes nothing rather than inventing a hold")
  void unmeteredRunClosesNothing() {
    reservationService.release(null, "done");
    reservationService.release("  ", "done");

    verify(creditClient, never()).release(anyString(), anyString());
  }

  @Test
  @DisplayName("A billing outage never fails a run that already finished")
  void billingOutageDoesNotFailAFinishedRun() {
    doThrow(new IllegalStateException("billing unreachable"))
        .when(creditClient)
        .settleAggregate(anyString(), any(), anyString());

    assertThatCode(
            () -> reservationService.settle("hold-7", UUID.randomUUID(), List.of(step(IMAGE))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("[ADR-041] A run settles ONCE, at the sum of what its steps measured")
  void settlesOnceAtTheSumOfTheSteps() {
    when(creditClient.settleAggregate(anyString(), any(), anyString())).thenReturn(true);
    UUID runId = UUID.randomUUID();

    reservationService.settle(
        "hold-7", runId, List.of(step(BillableCapability.CHAT), step(IMAGE), step(VIDEO)));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MeteredStep>> steps = ArgumentCaptor.forClass(List.class);
    verify(creditClient).settleAggregate(eq("hold-7"), steps.capture(), eq("studio-run-" + runId));
    verify(creditClient, never()).release(anyString(), anyString());
    // Three capabilities in one settlement — the whole reason settleMeasured could not be used:
    // it prices one report against the HOLD's rate, and this run spans three different units.
    assertThat(steps.getValue()).hasSize(3);
    assertThat(steps.getValue())
        .extracting(MeteredStep::capability)
        .containsExactly(BillableCapability.CHAT, IMAGE, VIDEO);
  }

  @Test
  @DisplayName("A run that measured nothing is released, never billed at its estimate")
  void unmeasuredRunIsReleased() {
    reservationService.settle("hold-7", UUID.randomUUID(), List.of());

    verify(creditClient).release(eq("hold-7"), anyString());
    verify(creditClient, never()).settleAggregate(anyString(), any(), anyString());
  }

  @Test
  @DisplayName("Nothing priceable in what was measured falls back to a release, not a zero debit")
  void nothingPriceableIsReleased() {
    when(creditClient.settleAggregate(anyString(), any(), anyString())).thenReturn(false);

    reservationService.settle("hold-7", UUID.randomUUID(), List.of(step(IMAGE)));

    verify(creditClient).release(eq("hold-7"), anyString());
  }

  private static final BillableCapability IMAGE = BillableCapability.IMAGE;
  private static final BillableCapability VIDEO = BillableCapability.VIDEO;

  /** One measured step: enough frames to price, whatever unit its capability turns out to use. */
  private static MeteredStep step(BillableCapability capability) {
    return new MeteredStep(
        capability,
        null,
        new ConsumptionReport(null, 300, 30, 1, 4, 1024, 1024, null, null, 1200L, null));
  }
}
