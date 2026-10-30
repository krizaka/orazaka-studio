package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studioservice.application.service.IdempotentRunStartService;
import com.krizaka.orazaka.studioservice.application.service.RunEventService;
import com.krizaka.orazaka.studioservice.application.service.StudioRunService;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.DenyRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.RunResponse;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.SendRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

class StudioRunControllerTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final UUID RUN = UUID.fromString("9f1c0a10-0000-4000-8000-000000000020");
  private static final Jwt ACTOR_JWT =
      Jwt.withTokenValue("t").header("alg", "HS256").subject(ACTOR).build();

  private final StudioRunService runService = mock(StudioRunService.class);
  private final StudioRunController controller =
      new StudioRunController(
          runService, mock(IdempotentRunStartService.class), mock(RunEventService.class));

  private static RunDetail run(RunStatus status) {
    return new RunDetail(
        RUN,
        null,
        "trade-showcase",
        "1.0.0",
        status,
        "hold-1",
        List.of(),
        null,
        Instant.parse("2026-10-30T10:00:00Z"),
        null,
        List.of(),
        null,
        null);
  }

  @Test
  @DisplayName("Send hands the message to the run, then answers the run as it stands")
  void sendAnswersTheRun() {
    when(runService.find(RUN, ACTOR)).thenReturn(Optional.of(run(RunStatus.RUNNING)));

    ResponseEntity<RunResponse> response =
        controller.send(RUN, new SendRequest("go ahead"), ACTOR_JWT);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().status()).isEqualTo(RunStatus.RUNNING);
    var order = inOrder(runService);
    order.verify(runService).send(RUN, ACTOR, "go ahead");
    order.verify(runService).find(RUN, ACTOR);
  }

  @Test
  @DisplayName("A message must say something")
  void sendRefusesABlankMessage() {
    assertThatThrownBy(() -> new SendRequest(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SendRequest(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Deny passes the reason, and the answer is the cancelled run")
  void denyAnswersTheCancelledRun() {
    when(runService.find(RUN, ACTOR)).thenReturn(Optional.of(run(RunStatus.CANCELLED)));

    ResponseEntity<RunResponse> response =
        controller.deny(RUN, new DenyRequest("wrong amount"), ACTOR_JWT);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().status()).isEqualTo(RunStatus.CANCELLED);
    verify(runService).deny(RUN, ACTOR, "wrong amount");
  }

  @Test
  @DisplayName("Deny tolerates an absent body: the reason is optional")
  void denyToleratesNoBody() {
    when(runService.find(RUN, ACTOR)).thenReturn(Optional.of(run(RunStatus.CANCELLED)));

    controller.deny(RUN, null, ACTOR_JWT);

    verify(runService).deny(RUN, ACTOR, null);
  }

  @Test
  @DisplayName("A run that is not the caller's is not found")
  void notTheCallersRun() {
    when(runService.find(RUN, ACTOR)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.deny(RUN, null, ACTOR_JWT))
        .isInstanceOf(RunNotFoundException.class);
  }
}
