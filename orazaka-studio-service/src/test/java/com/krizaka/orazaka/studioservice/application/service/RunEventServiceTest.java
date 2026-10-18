package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.TooManyRunStreamsException;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A client follows a run instead of polling it (orazaka#189, ADR-083). */
class RunEventServiceTest {

  private static final String ACTOR = "actor-1";

  /** A clock the sleeper moves, so no test waits for real. */
  private static final class FakeClock extends Clock {
    long now = 1_000_000L;

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }

    @Override
    public long millis() {
      return now;
    }
  }

  private static final class RecordingSink implements RunEventService.Sink {
    final List<String> events = new ArrayList<>();
    boolean open = true;
    int closeAfter = Integer.MAX_VALUE;

    private void note(String event) {
      events.add(event);
      if (events.size() >= closeAfter) {
        open = false;
      }
    }

    @Override
    public void snapshot(long id, RunDetail run) {
      note("run#" + id + ":" + run.status());
    }

    @Override
    public void done(long id, RunDetail run) {
      note("done#" + id + ":" + run.status());
    }

    @Override
    public void heartbeat() {
      note("heartbeat");
    }

    @Override
    public boolean open() {
      return open;
    }
  }

  private final StudioRunService runs = mock(StudioRunService.class);
  private final StudioRuntimeConfigService config = mock(StudioRuntimeConfigService.class);
  private final FakeClock clock = new FakeClock();
  private final UUID runId = UUID.randomUUID();
  private RunEventService events;

  @BeforeEach
  void wire() {
    when(config.intValue(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
    events = new RunEventService(runs, config, clock, millis -> clock.now += millis);
  }

  private RunDetail run(RunStatus status) {
    return new RunDetail(
        runId,
        null,
        "compliance-check",
        "1.0.0",
        status,
        null,
        null,
        null,
        Instant.EPOCH,
        null,
        null);
  }

  /** The run reads as these states in turn, the last one for ever. */
  private void reads(RunDetail... states) {
    java.util.Iterator<RunDetail> next = List.of(states).iterator();
    RunDetail[] current = new RunDetail[1];
    when(runs.find(runId, ACTOR))
        .thenAnswer(
            call -> {
              if (next.hasNext()) {
                current[0] = next.next();
              }
              return Optional.of(current[0]);
            });
  }

  @Test
  @DisplayName("the current state is sent at once, and again only when it changes")
  void sendsOnChangeOnly() throws Exception {
    RunDetail running = run(RunStatus.RUNNING);
    reads(running, running, running, run(RunStatus.AWAITING_INPUT), run(RunStatus.SUCCEEDED));
    RecordingSink sink = new RecordingSink();

    try (var subscription = events.subscribe(runId, ACTOR)) {
      subscription.pump(sink);
    }

    assertThat(sink.events)
        .containsExactly("run#1:RUNNING", "run#2:AWAITING_INPUT", "done#3:SUCCEEDED");
  }

  @Test
  @DisplayName("a run already over answers one done event and the stream ends")
  void finishedRunEndsAtOnce() throws Exception {
    reads(run(RunStatus.FAILED));
    RecordingSink sink = new RecordingSink();

    try (var subscription = events.subscribe(runId, ACTOR)) {
      subscription.pump(sink);
    }

    assertThat(sink.events).containsExactly("done#1:FAILED");
  }

  @Test
  @DisplayName("a quiet stream sends a heartbeat so a proxy does not close it")
  void heartbeatWhenQuiet() throws Exception {
    RunDetail running = run(RunStatus.RUNNING);
    when(runs.find(runId, ACTOR)).thenReturn(Optional.of(running));
    RecordingSink sink = new RecordingSink();
    sink.closeAfter = 3;

    try (var subscription = events.subscribe(runId, ACTOR)) {
      subscription.pump(sink);
    }

    // 15 s of silence at a 1.5 s poll: a heartbeat every 10th read.
    assertThat(sink.events).containsExactly("run#1:RUNNING", "heartbeat", "heartbeat");
    assertThat(clock.now - 1_000_000L).isBetween(29_000L, 32_000L);
  }

  @Test
  @DisplayName("the stream stops when the client is gone")
  void stopsWhenTheClientLeaves() throws Exception {
    when(runs.find(runId, ACTOR)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
    RecordingSink sink = new RecordingSink();
    sink.closeAfter = 1;

    try (var subscription = events.subscribe(runId, ACTOR)) {
      subscription.pump(sink);
    }

    assertThat(sink.events).containsExactly("run#1:RUNNING");
  }

  @Test
  @DisplayName("a stream has a time limit: the client reconnects and gets a fresh snapshot")
  void endsAtTheMaximumDuration() throws Exception {
    when(config.intValue("run.events.max-seconds", 1800)).thenReturn(10);
    when(runs.find(runId, ACTOR)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
    RecordingSink sink = new RecordingSink();

    try (var subscription = events.subscribe(runId, ACTOR)) {
      subscription.pump(sink);
    }

    assertThat(clock.now - 1_000_000L).isBetween(10_000L, 12_000L);
    assertThat(sink.events).first().isEqualTo("run#1:RUNNING");
  }

  @Test
  @DisplayName("a run that is not the actor's is not found, as for a plain read")
  void notTheActorsRun() {
    when(runs.find(runId, "someone-else")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> events.subscribe(runId, "someone-else"))
        .isInstanceOf(RunNotFoundException.class);
  }

  @Test
  @DisplayName("an actor holds a bounded number of streams; closing one frees a slot")
  void boundedStreamsPerActor() {
    when(config.intValue("run.max-streams-per-actor", 5)).thenReturn(2);
    when(runs.find(runId, ACTOR)).thenReturn(Optional.of(run(RunStatus.RUNNING)));

    var one = events.subscribe(runId, ACTOR);
    var two = events.subscribe(runId, ACTOR);
    assertThatThrownBy(() -> events.subscribe(runId, ACTOR))
        .isInstanceOf(TooManyRunStreamsException.class);
    one.close();
    one.close(); // twice is harmless
    var three = events.subscribe(runId, ACTOR);

    assertThat(three).isNotNull();
    two.close();
    three.close();
  }
}
