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
import com.krizaka.orazaka.studioservice.domain.port.RunEventLog;
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
  private final RunEventLog log = mock(RunEventLog.class);
  private final FakeClock clock = new FakeClock();
  private final UUID runId = UUID.randomUUID();
  private RunEventService events;

  @BeforeEach
  void wire() {
    when(config.intValue(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
    events = new RunEventService(runs, config, log, clock, millis -> clock.now += millis);
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
        null,
        null);
  }

  /**
   * The run's number reads as these values in turn, the last one for ever; the run itself reads as
   * the state that goes with the number last read.
   */
  private void story(long[] seqs, RunDetail... states) {
    int[] at = {-1};
    when(log.currentSeq(runId, ACTOR))
        .thenAnswer(
            call -> {
              if (at[0] < seqs.length - 1) {
                at[0]++;
              }
              return java.util.OptionalLong.of(seqs[at[0]]);
            });
    when(runs.find(runId, ACTOR))
        .thenAnswer(call -> Optional.of(states[Math.min(Math.max(at[0], 0), states.length - 1)]));
  }

  @Test
  @DisplayName(
      "the current state is sent at once, and again only when the run's number moves; the id is"
          + " that number")
  void sendsOnChangeOnly() throws Exception {
    // numbers read: 4 (subscribe is a find, not a number), then 4 4 5 7
    story(
        new long[] {4, 4, 4, 5, 7},
        run(RunStatus.RUNNING),
        run(RunStatus.RUNNING),
        run(RunStatus.RUNNING),
        run(RunStatus.AWAITING_INPUT),
        run(RunStatus.SUCCEEDED));
    RecordingSink sink = new RecordingSink();

    try (var subscription = events.subscribe(runId, ACTOR)) {
      subscription.pump(sink);
    }

    assertThat(sink.events)
        .containsExactly("run#4:RUNNING", "run#5:AWAITING_INPUT", "done#7:SUCCEEDED");
  }

  @Test
  @DisplayName(
      "a client that comes back with Last-Event-ID is sent nothing until the run moves past it")
  void resumesAfterLastEventId() throws Exception {
    story(
        new long[] {5, 5, 6},
        run(RunStatus.RUNNING),
        run(RunStatus.RUNNING),
        run(RunStatus.AWAITING_INPUT));
    RecordingSink sink = new RecordingSink();
    sink.closeAfter = 1;

    try (var subscription = events.subscribe(runId, ACTOR, 5)) {
      subscription.pump(sink);
    }

    assertThat(sink.events).containsExactly("run#6:AWAITING_INPUT");
  }

  @Test
  @DisplayName(
      "a client that missed several changes gets the run as it is now, under the latest number")
  void missedChangesAreFoldedIntoTheCurrentState() throws Exception {
    story(new long[] {9}, run(RunStatus.AWAITING_INPUT));
    RecordingSink sink = new RecordingSink();
    sink.closeAfter = 1;

    try (var subscription = events.subscribe(runId, ACTOR, 3)) {
      subscription.pump(sink);
    }

    assertThat(sink.events).containsExactly("run#9:AWAITING_INPUT");
  }

  @Test
  @DisplayName("a run already over answers one done event under its number and the stream ends")
  void finishedRunEndsAtOnce() throws Exception {
    story(new long[] {12}, run(RunStatus.FAILED));
    RecordingSink sink = new RecordingSink();

    try (var subscription = events.subscribe(runId, ACTOR)) {
      subscription.pump(sink);
    }

    assertThat(sink.events).containsExactly("done#12:FAILED");
  }

  @Test
  @DisplayName("a client that already saw the end of a finished run is told to stop reconnecting")
  void alreadySeenTheEnd() {
    story(new long[] {12}, run(RunStatus.SUCCEEDED));
    assertThat(events.alreadySeenTheEnd(runId, ACTOR, 12)).isTrue();
    assertThat(events.alreadySeenTheEnd(runId, ACTOR, 15)).isTrue();
    assertThat(events.alreadySeenTheEnd(runId, ACTOR, 11)).as("it missed the end").isFalse();
    assertThat(events.alreadySeenTheEnd(runId, ACTOR, -1)).as("a first connection").isFalse();
  }

  @Test
  @DisplayName("a run still going is never 'seen to its end'")
  void aRunStillGoingIsNeverSeenToItsEnd() {
    story(new long[] {12}, run(RunStatus.RUNNING));
    assertThat(events.alreadySeenTheEnd(runId, ACTOR, 12)).isFalse();
  }

  @Test
  @DisplayName("a quiet stream sends a heartbeat so a proxy does not close it")
  void heartbeatWhenQuiet() throws Exception {
    story(new long[] {1}, run(RunStatus.RUNNING));
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
    story(new long[] {1}, run(RunStatus.RUNNING));
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
    story(new long[] {1}, run(RunStatus.RUNNING));
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

  // ── the per-key stream: every run of the actor, one connection (orazaka#189) ─────────────────

  private RunDetail runOf(UUID id, RunStatus status) {
    return new RunDetail(
        id,
        null,
        "compliance-check",
        "1.0.0",
        status,
        null,
        null,
        null,
        Instant.EPOCH,
        null,
        null,
        null);
  }

  /** The actor's history reads as these lists in turn, the last one for ever. */
  private void historyReads(List<RunDetail>... states) {
    java.util.Iterator<List<RunDetail>> next = List.of(states).iterator();
    List<List<RunDetail>> current = new ArrayList<>();
    current.add(List.of());
    when(runs.history(ACTOR, 50))
        .thenAnswer(
            call -> {
              if (next.hasNext()) {
                current.set(0, next.next());
              }
              return current.getFirst();
            });
  }

  private static final class IdSink implements RunEventService.Sink {
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
      note("run#" + id + ":" + run.id().toString().substring(0, 1) + ":" + run.status());
    }

    @Override
    public void done(long id, RunDetail run) {
      note("done#" + id + ":" + run.id().toString().substring(0, 1) + ":" + run.status());
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

  private static final UUID A = UUID.fromString("a0000000-0000-0000-0000-000000000001");
  private static final UUID B = UUID.fromString("b0000000-0000-0000-0000-000000000002");
  private static final UUID C = UUID.fromString("c0000000-0000-0000-0000-000000000003");

  @Test
  @DisplayName(
      "the all-runs stream sends the runs in flight at once, then each change, finished ones as"
          + " done")
  @SuppressWarnings("unchecked")
  void allRunsSendsInFlightThenChanges() throws Exception {
    historyReads(
        List.of(runOf(A, RunStatus.RUNNING), runOf(C, RunStatus.SUCCEEDED)),
        List.of(runOf(A, RunStatus.RUNNING), runOf(C, RunStatus.SUCCEEDED)),
        List.of(
            runOf(B, RunStatus.RUNNING),
            runOf(A, RunStatus.AWAITING_INPUT),
            runOf(C, RunStatus.SUCCEEDED)),
        List.of(
            runOf(B, RunStatus.SUCCEEDED),
            runOf(A, RunStatus.AWAITING_INPUT),
            runOf(C, RunStatus.SUCCEEDED)));
    IdSink sink = new IdSink();
    sink.closeAfter = 4;

    try (var subscription = events.subscribeAll(ACTOR)) {
      subscription.pump(sink);
    }

    // C was over before the stream opened: not replayed. A is in flight: sent. Then A changes and B
    // appears
    // (oldest first within one read), then B ends.
    assertThat(sink.events)
        .containsExactly(
            "run#1:a:RUNNING", "run#2:a:AWAITING_INPUT", "run#3:b:RUNNING", "done#4:b:SUCCEEDED");
  }

  @Test
  @DisplayName("the all-runs stream sends a heartbeat when quiet, and ends at the time limit")
  @SuppressWarnings("unchecked")
  void allRunsHeartbeatAndTimeLimit() throws Exception {
    when(config.intValue("run.events.max-seconds", 1800)).thenReturn(20);
    historyReads(List.of());
    IdSink sink = new IdSink();

    try (var subscription = events.subscribeAll(ACTOR)) {
      subscription.pump(sink);
    }

    assertThat(sink.events).isNotEmpty().allMatch("heartbeat"::equals);
    assertThat(clock.now - 1_000_000L).isBetween(20_000L, 22_000L);
  }

  @Test
  @DisplayName(
      "the all-runs stream takes one of the actor's stream slots, like a single-run stream")
  void allRunsHoldsASlot() {
    when(config.intValue("run.max-streams-per-actor", 5)).thenReturn(1);

    var all = events.subscribeAll(ACTOR);
    assertThatThrownBy(() -> events.subscribeAll(ACTOR))
        .isInstanceOf(TooManyRunStreamsException.class);
    all.close();

    assertThat(events.subscribeAll(ACTOR)).isNotNull();
  }
}
