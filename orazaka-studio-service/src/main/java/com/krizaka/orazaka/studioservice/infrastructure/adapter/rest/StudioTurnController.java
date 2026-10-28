package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studioservice.application.service.CodeTurnService;
import com.krizaka.orazaka.studioservice.application.service.StudioSessionService;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.TurnRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.TurnResponse;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The model turns of a client-executed agent session (ADR-093 §6, ADR-086): the Mac app sends the
 * conversation and the tool results of its last calls, and receives the next calls to run. The
 * Studio gates every turn; the engine runs the model and never the tools.
 */
@RestController
@RequestMapping("/api/v1/studio-runs")
class StudioTurnController {

  private final CodeTurnService turns;
  private final StudioSessionService sessions;

  StudioTurnController(CodeTurnService turns, StudioSessionService sessions) {
    this.turns = Objects.requireNonNull(turns, "CodeTurnService cannot be null");
    this.sessions = Objects.requireNonNull(sessions, "StudioSessionService cannot be null");
  }

  /**
   * The client's « finish »: ends the session, and the run settles. Idempotent.
   *
   * @param runId the session's Studio run
   * @param actor the authenticated caller
   * @return {@code 204}; {@code 404} when the run is not the caller's
   */
  @PostMapping("/{runId}/finish")
  ResponseEntity<Void> finish(@PathVariable UUID runId, @AuthenticationPrincipal Jwt actor) {
    sessions.finish(runId, actor.getSubject());
    return ResponseEntity.noContent().build();
  }

  /**
   * Runs one model turn of a session.
   *
   * @param runId the session's Studio run
   * @param request the system prompt, the conversation and the tools
   * @param actor the authenticated caller
   * @return {@code 200} with the text, the calls to run and the usage; {@code 404} when the run is
   *     not the caller's or not a code run; {@code 409} {@code studio_not_entitled} when the pack
   *     is not held on this turn, or {@code run_refused} (with a {@code reason}: finished,
   *     cancelled, expired, failed) when the session is over; the engine's own refusals ({@code
   *     consent_required}, {@code insufficient_credits}) as it states them
   */
  @PostMapping("/{runId}/turns")
  TurnResponse turn(
      @PathVariable UUID runId,
      @RequestBody TurnRequest request,
      @AuthenticationPrincipal Jwt actor) {
    return TurnResponse.from(
        turns.turn(
            runId,
            actor.getSubject(),
            request.system(),
            request.domainMessages(),
            request.domainTools()));
  }
}
