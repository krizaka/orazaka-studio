package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studioservice.application.service.StudioRunService;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.ContentDecision;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.RunRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.RunResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The runs sub-resource: executing an installation (ADR-034 §10).
 *
 * <p>Starting a run answers {@code 202} with an id, never a result: a run is a saga of durable jobs
 * that can last minutes, and its progress reaches the browser over the job plane's existing SSE
 * relay rather than a second stream this controller would have to invent.
 */
@RestController
@RequestMapping("/api/v1/studios")
class StudioRunController {

  private static final int DEFAULT_HISTORY_LIMIT = 50;

  private final StudioRunService runService;

  StudioRunController(StudioRunService runService) {
    this.runService = Objects.requireNonNull(runService, "StudioRunService cannot be null");
  }

  /**
   * Starts a run.
   *
   * @param installationId the installation to execute
   * @param request the actor's answers to the run form
   * @param actor the authenticated caller
   * @return {@code 202} with the accepted run; {@code 402} when the estimate is unaffordable
   */
  @PostMapping("/installations/{installationId}/runs")
  ResponseEntity<RunResponse> start(
      @PathVariable UUID installationId,
      @RequestBody(required = false) RunRequest request,
      @AuthenticationPrincipal Jwt actor) {
    RunRequest resolved = request == null ? new RunRequest(null) : request;
    return ResponseEntity.accepted()
        .body(
            RunResponse.from(
                runService.start(installationId, actor.getSubject(), resolved.inputs())));
  }

  /**
   * Starts a run of a Studio, addressed by its key rather than by an installation.
   *
   * <p>The entry a TOOLKIT is run through — its installation is derived and has no id — and a
   * VERTICAL reached through the caller's installation (ADR-061). Refusals share one shape: {@code
   * 409} carrying the pack to open, whether the caller is unentitled or has not installed it.
   *
   * @param studioKey the Studio to run
   * @param request the actor's answers to the run form
   * @param actor the authenticated caller
   * @return {@code 202} with the accepted run
   */
  @PostMapping("/{studioKey}/runs")
  ResponseEntity<RunResponse> startStudio(
      @PathVariable String studioKey,
      @RequestBody(required = false) RunRequest request,
      @AuthenticationPrincipal Jwt actor) {
    RunRequest resolved = request == null ? new RunRequest(null) : request;
    return ResponseEntity.accepted()
        .body(RunResponse.from(runService.start(studioKey, actor.getSubject(), resolved.inputs())));
  }

  /**
   * Dry-run of the content policy: what would a run with these inputs do? Nothing is held, stored
   * or sent to a provider.
   *
   * @param studioKey the Studio
   * @param request the would-be answers
   * @param actor the authenticated caller
   * @return {@code 200} with the labels, the decision and the rule that decided
   */
  @PostMapping("/{studioKey}/policy-check")
  ResponseEntity<Map<String, Object>> policyCheck(
      @PathVariable String studioKey,
      @RequestBody(required = false) RunRequest request,
      @AuthenticationPrincipal Jwt actor) {
    RunRequest resolved = request == null ? new RunRequest(null) : request;
    ContentDecision d = runService.checkPolicy(studioKey, actor.getSubject(), resolved.inputs());
    return ResponseEntity.ok(
        Map.of(
            "decision", d.verb(),
            "refused", d.refused(),
            "rule", d.rule(),
            "labels", d.labels().stream().map(Enum::name).sorted().toList(),
            "warnings", d.warnings(),
            "providers", d.providers()));
  }

  /**
   * This actor's run history.
   *
   * @param limit how many to return
   * @param actor the authenticated caller
   * @return their runs, most recent first
   */
  @GetMapping("/runs")
  List<RunResponse> history(
      @RequestParam(required = false) Integer limit, @AuthenticationPrincipal Jwt actor) {
    int effective = limit == null || limit < 1 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, 200);
    return runService.history(actor.getSubject(), effective).stream()
        .map(RunResponse::from)
        .toList();
  }

  /**
   * One run with its per-step states and outputs.
   *
   * @param runId the run
   * @param actor the authenticated caller
   * @return the run
   * @throws RunNotFoundException when the id names nothing this actor owns
   */
  @GetMapping("/runs/{runId}")
  RunResponse find(@PathVariable UUID runId, @AuthenticationPrincipal Jwt actor) {
    return runService
        .find(runId, actor.getSubject())
        .map(RunResponse::from)
        .orElseThrow(() -> new RunNotFoundException(runId));
  }

  /**
   * Resolves an approval step, resuming the run.
   *
   * @param runId the parked run
   * @param actor the authenticated caller
   * @return {@code 204}
   */
  @PostMapping("/runs/{runId}/approve")
  ResponseEntity<Void> approve(@PathVariable UUID runId, @AuthenticationPrincipal Jwt actor) {
    runService.approve(runId, actor.getSubject());
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  /**
   * Cancels a run and releases its hold.
   *
   * @param runId the run
   * @param actor the authenticated caller
   * @return {@code 204}
   */
  @PostMapping("/runs/{runId}/cancel")
  ResponseEntity<Void> cancel(@PathVariable UUID runId, @AuthenticationPrincipal Jwt actor) {
    runService.cancel(runId, actor.getSubject());
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }
}
