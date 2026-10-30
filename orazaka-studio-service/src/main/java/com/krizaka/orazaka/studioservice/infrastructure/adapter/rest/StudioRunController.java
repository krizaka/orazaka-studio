package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studioservice.application.service.IdempotentRunStartService;
import com.krizaka.orazaka.studioservice.application.service.RunEventService;
import com.krizaka.orazaka.studioservice.application.service.StudioRunService;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.ContentDecision;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.DenyRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.ResumeRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.RunRequest;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.RunResponse;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.SendRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

  /** The header a client sets to make a retry of a run start safe (ADR-083). */
  static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /**
   * Set to {@code true} on an answer that replays the run an earlier request with this key started.
   */
  static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

  /** One virtual thread per open stream: a stream mostly waits. */
  private static final ExecutorService STREAMS = Executors.newVirtualThreadPerTaskExecutor();

  private final StudioRunService runService;
  private final IdempotentRunStartService starter;
  private final RunEventService events;

  StudioRunController(
      StudioRunService runService, IdempotentRunStartService starter, RunEventService events) {
    this.runService = Objects.requireNonNull(runService, "StudioRunService cannot be null");
    this.starter = Objects.requireNonNull(starter, "IdempotentRunStartService cannot be null");
    this.events = Objects.requireNonNull(events, "RunEventService cannot be null");
  }

  private ResponseEntity<RunResponse> accepted(IdempotentRunStartService.Started started) {
    ResponseEntity.BodyBuilder response = ResponseEntity.accepted();
    if (started.replayed()) {
      response.header(IDEMPOTENT_REPLAYED, "true");
    }
    return response.body(RunResponse.from(started.run()));
  }

  /**
   * Starts a run.
   *
   * @param installationId the installation to execute
   * @param request the actor's answers to the run form
   * @param idempotencyKey the optional {@code Idempotency-Key}: the same key and request answer the
   *     run it already started, flagged {@code Idempotent-Replayed: true}
   * @param actor the authenticated caller
   * @return {@code 202} with the accepted run; {@code 402} when the estimate is unaffordable;
   *     {@code 422} when the key started a different request
   */
  @PostMapping("/installations/{installationId}/runs")
  ResponseEntity<RunResponse> start(
      @PathVariable UUID installationId,
      @RequestBody(required = false) RunRequest request,
      @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
      @AuthenticationPrincipal Jwt actor) {
    RunRequest resolved = request == null ? new RunRequest(null) : request;
    return accepted(
        starter.start(
            "installation:" + installationId,
            actor.getSubject(),
            resolved.inputs(),
            idempotencyKey,
            () -> runService.start(installationId, actor.getSubject(), resolved.inputs())));
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
   * @param idempotencyKey the optional {@code Idempotency-Key}, as on the installation entry
   * @param actor the authenticated caller
   * @return {@code 202} with the accepted run
   */
  @PostMapping("/{studioKey}/runs")
  ResponseEntity<RunResponse> startStudio(
      @PathVariable String studioKey,
      @RequestBody(required = false) RunRequest request,
      @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
      @AuthenticationPrincipal Jwt actor) {
    RunRequest resolved = request == null ? new RunRequest(null) : request;
    return accepted(
        starter.start(
            "studio:" + studioKey,
            actor.getSubject(),
            resolved.inputs(),
            idempotencyKey,
            () -> runService.start(studioKey, actor.getSubject(), resolved.inputs())));
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
   * Follows a run as Server-Sent Events instead of polling it (orazaka#189, ADR-083).
   *
   * <p>Each event is a full snapshot of the run, as {@code GET /runs/{runId}} would answer: {@code
   * event: run} when it changes (and once at the start), {@code event: done} when it is over, after
   * which the stream ends. A reconnecting client receives the current state. A comment line every
   * 15 seconds keeps an idle connection open.
   *
   * @param runId the run
   * @param lastEventId the {@code Last-Event-ID} an {@code EventSource} sends when it reconnects
   * @param actor the authenticated caller
   * @return the stream; {@code 204} when the client has already seen the run's end; {@code 404}
   *     when the run is not the caller's, {@code 429} when the caller already holds as many streams
   *     as allowed
   */
  @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> events(
      @PathVariable UUID runId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
      @AuthenticationPrincipal Jwt actor) {
    long seen = parseEventId(lastEventId);
    if (events.alreadySeenTheEnd(runId, actor.getSubject(), seen)) {
      // 204 tells an EventSource not to reconnect: it has seen the run's last event.
      return ResponseEntity.noContent().build();
    }
    RunEventService.Subscription subscription = events.subscribe(runId, actor.getSubject(), seen);
    return ResponseEntity.ok(stream(subscription, subscription::pump));
  }

  private static long parseEventId(String header) {
    try {
      return header == null ? -1 : Math.max(-1, Long.parseLong(header.strip()));
    } catch (NumberFormatException malformed) {
      return -1;
    }
  }

  /**
   * Follows every run of the caller on one connection (the per-key stream of orazaka#189): {@code
   * event: run} whenever a run in flight changes, {@code event: done} when one ends, each carrying
   * the run as {@code GET /runs/{runId}} would. Runs that were over before the stream opened are
   * not replayed; read the history for them. It counts as one stream against the caller's limit,
   * and a comment line every 15 seconds keeps an idle connection open.
   *
   * @param actor the authenticated caller
   * @return the stream; {@code 429} when the caller already holds as many streams as allowed
   */
  @GetMapping(value = "/runs/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter allEvents(@AuthenticationPrincipal Jwt actor) {
    RunEventService.AllRunsSubscription subscription = events.subscribeAll(actor.getSubject());
    return stream(subscription, subscription::pump);
  }

  /** A pump that writes to a sink. */
  @FunctionalInterface
  private interface Pump {
    void run(RunEventService.Sink sink) throws IOException, InterruptedException;
  }

  private SseEmitter stream(AutoCloseable subscription, Pump pump) {
    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean open = new AtomicBoolean(true);
    Runnable close =
        () -> {
          try {
            subscription.close();
          } catch (Exception e) {
            // closing a slot cannot fail in a way a client could act on
          }
        };
    Runnable gone =
        () -> {
          open.set(false);
          close.run();
        };
    emitter.onCompletion(gone);
    emitter.onTimeout(gone);
    emitter.onError(e -> gone.run());
    STREAMS.execute(
        () -> {
          try {
            pump.run(new EmitterSink(emitter, open));
            emitter.complete();
          } catch (IOException e) {
            gone.run();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            gone.run();
          } catch (RuntimeException e) {
            emitter.completeWithError(e);
          } finally {
            close.run();
          }
        });
    return emitter;
  }

  /** Writes the service's events as SSE frames. */
  private record EmitterSink(SseEmitter emitter, AtomicBoolean alive)
      implements RunEventService.Sink {

    @Override
    public void snapshot(long id, RunDetail run) throws IOException {
      emitter.send(
          SseEmitter.event().id(String.valueOf(id)).name("run").data(RunResponse.from(run)));
    }

    @Override
    public void done(long id, RunDetail run) throws IOException {
      emitter.send(
          SseEmitter.event().id(String.valueOf(id)).name("done").data(RunResponse.from(run)));
    }

    @Override
    public void heartbeat() throws IOException {
      emitter.send(SseEmitter.event().comment("keep-alive"));
    }

    @Override
    public boolean open() {
      return alive.get();
    }
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
   * Answers a run that waits for input: the message is the parked step's output and the run goes on
   * (orazaka-studio#115).
   *
   * @param runId the parked run
   * @param request the message
   * @param actor the authenticated caller
   * @return {@code 200} with the run as it now stands
   */
  @PostMapping("/runs/{runId}/send")
  ResponseEntity<RunResponse> send(
      @PathVariable UUID runId,
      @RequestBody SendRequest request,
      @AuthenticationPrincipal Jwt actor) {
    runService.send(runId, actor.getSubject(), request.text());
    return ResponseEntity.ok(snapshot(runId, actor));
  }

  /**
   * Refuses the approval a run waits on: the run stops without the step and its hold is released
   * (orazaka-studio#115).
   *
   * @param runId the parked run
   * @param request the optional reason
   * @param actor the authenticated caller
   * @return {@code 200} with the run as it now stands
   */
  @PostMapping("/runs/{runId}/deny")
  ResponseEntity<RunResponse> deny(
      @PathVariable UUID runId,
      @RequestBody(required = false) DenyRequest request,
      @AuthenticationPrincipal Jwt actor) {
    runService.deny(runId, actor.getSubject(), request == null ? null : request.reason());
    return ResponseEntity.ok(snapshot(runId, actor));
  }

  private RunResponse snapshot(UUID runId, Jwt actor) {
    return runService
        .find(runId, actor.getSubject())
        .map(RunResponse::from)
        .orElseThrow(() -> new RunNotFoundException(runId));
  }

  /**
   * Starts a new run from a step of a finished one (orazaka-studio#63): the steps before it keep
   * their recorded outputs and only the re-run steps are billed.
   *
   * @param runId the finished run
   * @param request the step to run again
   * @param actor the authenticated caller
   * @return {@code 202} with the new run, which names its origin in {@code resumedFrom}
   */
  @PostMapping("/runs/{runId}/resume")
  ResponseEntity<RunResponse> resume(
      @PathVariable UUID runId,
      @RequestBody ResumeRequest request,
      @AuthenticationPrincipal Jwt actor) {
    return ResponseEntity.accepted()
        .body(RunResponse.from(runService.resume(runId, actor.getSubject(), request.fromStep())));
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
