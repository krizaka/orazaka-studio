package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.TooManyRunStreamsException;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.studioservice.domain.port.RunEventLog;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Follows a run for a client that would otherwise poll it (orazaka#189, ADR-083).
 *
 * <p>Every event is a <b>full snapshot</b> of the run, sent when it changes and once at the start,
 * so a client that reconnects — an {@code EventSource} does it by itself — simply receives the
 * current state: nothing is replayed and nothing can be missed. The run is read from the database
 * on a short interval, which keeps this correct with several instances and costs one cheap read per
 * watcher per interval; a push from the saga is the later optimisation (ADR-083, slice 10).
 *
 * <p>A stream ends when the run is over ({@code done}), when the client is gone, or after {@code
 * run.events.max-seconds}. Quiet streams send a heartbeat so a proxy does not close them.
 */
@Service
public class RunEventService {

  /** Row of {@code studio_runtime_config}: streams one actor may hold open (per instance). */
  static final String MAX_STREAMS_KEY = "run.max-streams-per-actor";

  static final String MAX_SECONDS_KEY = "run.events.max-seconds";
  static final String POLL_MILLIS_KEY = "run.events.poll-millis";
  static final String HEARTBEAT_SECONDS_KEY = "run.events.heartbeat-seconds";

  private static final Set<RunStatus> TERMINAL =
      Set.of(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED);

  /** Where the events go; the REST adapter writes them as Server-Sent Events. */
  public interface Sink {

    /**
     * The run, as it is now ({@code event: run}).
     *
     * @param id the event number: the run's own on a single-run stream, the connection's from 1 on
     *     the all-runs one
     * @param run the run
     * @throws IOException when the client is gone
     */
    void snapshot(long id, RunDetail run) throws IOException;

    /**
     * The run, final ({@code event: done}); the stream ends after it.
     *
     * @param id the event number on this connection
     * @param run the run
     * @throws IOException when the client is gone
     */
    void done(long id, RunDetail run) throws IOException;

    /**
     * A comment line that keeps an idle connection open.
     *
     * @throws IOException when the client is gone
     */
    void heartbeat() throws IOException;

    /**
     * Whether the client is still there.
     *
     * @return {@code false} once the client disconnected
     */
    boolean open();
  }

  /** Waits between two reads; replaced in tests. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private final StudioRunService runs;
  private final StudioRuntimeConfigService config;
  private final RunEventLog log;
  private final Clock clock;
  private final Sleeper sleeper;
  private final Map<String, AtomicInteger> openByActor = new ConcurrentHashMap<>();

  @Autowired
  public RunEventService(
      StudioRunService runs, StudioRuntimeConfigService config, RunEventLog log) {
    this(runs, config, log, Clock.systemUTC(), Thread::sleep);
  }

  RunEventService(
      StudioRunService runs,
      StudioRuntimeConfigService config,
      RunEventLog log,
      Clock clock,
      Sleeper sleeper) {
    this.runs = Objects.requireNonNull(runs, "StudioRunService cannot be null");
    this.config = Objects.requireNonNull(config, "StudioRuntimeConfigService cannot be null");
    this.log = Objects.requireNonNull(log, "RunEventLog cannot be null");
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /**
   * Opens a stream on a run: checks the run is the actor's and takes one of the actor's slots.
   *
   * @param runId the run
   * @param actorId the opaque billable subject
   * @return the subscription; {@link Subscription#close()} gives the slot back
   * @throws RunNotFoundException when the id names nothing this actor owns
   * @throws TooManyRunStreamsException when the actor holds as many streams as allowed
   */
  public Subscription subscribe(UUID runId, String actorId) {
    return subscribe(runId, actorId, -1);
  }

  /**
   * Opens a stream that resumes after a client's {@code Last-Event-ID}.
   *
   * @param runId the run
   * @param actorId the opaque billable subject
   * @param lastEventId the last event number the client saw, or a negative number for none
   * @return the subscription
   * @throws RunNotFoundException when the id names nothing this actor owns
   * @throws TooManyRunStreamsException when the actor holds as many streams as allowed
   */
  public Subscription subscribe(UUID runId, String actorId, long lastEventId) {
    runs.find(runId, actorId).orElseThrow(() -> new RunNotFoundException(runId));
    return new Subscription(runId, actorId, takeSlot(actorId), lastEventId);
  }

  /**
   * Whether a client that saw event {@code lastEventId} has already seen the end of the run: it is
   * over and nothing changed since. Such a client is told to stop reconnecting.
   *
   * @param runId the run
   * @param actorId the opaque billable subject
   * @param lastEventId the last event number the client saw
   * @return {@code true} when there is nothing left to send, ever
   */
  public boolean alreadySeenTheEnd(UUID runId, String actorId, long lastEventId) {
    if (lastEventId < 0) {
      return false;
    }
    boolean over =
        runs.find(runId, actorId).map(run -> TERMINAL.contains(run.status())).orElse(false);
    return over && log.currentSeq(runId, actorId).orElse(Long.MAX_VALUE) <= lastEventId;
  }

  /**
   * Opens a stream on every run of the actor: one connection instead of one per run (the per-key
   * stream of orazaka#189). It takes one of the same stream slots as a single-run stream.
   *
   * @param actorId the opaque billable subject
   * @return the subscription; {@link AllRunsSubscription#close()} gives the slot back
   * @throws TooManyRunStreamsException when the actor holds as many streams as allowed
   */
  public AllRunsSubscription subscribeAll(String actorId) {
    return new AllRunsSubscription(actorId, takeSlot(actorId));
  }

  private AtomicInteger takeSlot(String actorId) {
    int limit = Math.max(1, config.intValue(MAX_STREAMS_KEY, 5));
    AtomicInteger open = openByActor.computeIfAbsent(actorId, k -> new AtomicInteger());
    if (open.incrementAndGet() > limit) {
      open.decrementAndGet();
      throw new TooManyRunStreamsException(limit);
    }
    return open;
  }

  /** One open stream. */
  public final class Subscription implements AutoCloseable {

    private final UUID runId;
    private final String actorId;
    private final AtomicInteger open;
    private final long lastEventId;
    private boolean closed;

    private Subscription(UUID runId, String actorId, AtomicInteger open, long lastEventId) {
      this.runId = runId;
      this.actorId = actorId;
      this.open = open;
      this.lastEventId = lastEventId;
    }

    /**
     * Sends the run as it is now, each time its number moves, until it is over, the client leaves
     * or the stream's time is up. The event id is the run's own number (see {@link RunEventLog}),
     * the same on every instance, so a client that comes back with {@code Last-Event-ID} is sent
     * something only when the run moved since; the snapshot is the run's current state, so the
     * changes it missed are folded into it. Blocks; run it on its own (virtual) thread.
     *
     * @param sink where the events go
     * @throws IOException when the client is gone
     * @throws InterruptedException when the thread is interrupted
     */
    public void pump(Sink sink) throws IOException, InterruptedException {
      long deadline = clock.millis() + config.intValue(MAX_SECONDS_KEY, 1800) * 1000L;
      long heartbeatMillis = Math.max(1, config.intValue(HEARTBEAT_SECONDS_KEY, 15)) * 1000L;
      long pollMillis = Math.max(100, config.intValue(POLL_MILLIS_KEY, 1500));
      long lastSent = clock.millis();
      long sent = lastEventId;
      while (sink.open() && clock.millis() < deadline) {
        long seq = log.currentSeq(runId, actorId).orElse(-1);
        if (seq < 0) {
          return;
        }
        if (seq > sent) {
          RunDetail run = runs.find(runId, actorId).orElse(null);
          if (run == null) {
            return;
          }
          sent = seq;
          lastSent = clock.millis();
          if (TERMINAL.contains(run.status())) {
            sink.done(seq, run);
            return;
          }
          sink.snapshot(seq, run);
        } else if (clock.millis() - lastSent >= heartbeatMillis) {
          lastSent = clock.millis();
          sink.heartbeat();
        }
        sleeper.sleep(pollMillis);
      }
    }

    /** Gives the slot back; safe to call twice. */
    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        open.decrementAndGet();
      }
    }
  }

  /** How many of the actor's latest runs the all-runs stream watches. */
  static final int ALL_RUNS_WATCHED = 50;

  /** One open stream on every run of an actor. */
  public final class AllRunsSubscription implements AutoCloseable {

    private final String actorId;
    private final AtomicInteger open;
    private boolean closed;

    private AllRunsSubscription(String actorId, AtomicInteger open) {
      this.actorId = actorId;
      this.open = open;
    }

    /**
     * Sends the runs in flight at once, then every change of any of the actor's latest runs: {@code
     * snapshot} for a run that moves, {@code done} for one that ends (once). A run that was over
     * before the stream opened is not replayed. Ids are the number of the event on this connection.
     * Blocks; run it on its own (virtual) thread; ends when the client leaves or the stream's time
     * is up.
     *
     * @param sink where the events go
     * @throws IOException when the client is gone
     * @throws InterruptedException when the thread is interrupted
     */
    public void pump(Sink sink) throws IOException, InterruptedException {
      long deadline = clock.millis() + config.intValue(MAX_SECONDS_KEY, 1800) * 1000L;
      long heartbeatMillis = Math.max(1, config.intValue(HEARTBEAT_SECONDS_KEY, 15)) * 1000L;
      long pollMillis = Math.max(100, config.intValue(POLL_MILLIS_KEY, 1500));
      long lastSent = clock.millis();
      long id = 0;
      Map<UUID, RunDetail> seen = new HashMap<>();
      boolean first = true;
      while (sink.open() && clock.millis() < deadline) {
        for (RunDetail run : runs.history(actorId, ALL_RUNS_WATCHED).reversed()) {
          RunDetail before = seen.put(run.id(), run);
          boolean over = TERMINAL.contains(run.status());
          if (run.equals(before) || (first && over)) {
            continue;
          }
          id++;
          lastSent = clock.millis();
          if (over) {
            sink.done(id, run);
          } else {
            sink.snapshot(id, run);
          }
        }
        first = false;
        if (clock.millis() - lastSent >= heartbeatMillis) {
          lastSent = clock.millis();
          sink.heartbeat();
        }
        sleeper.sleep(pollMillis);
      }
    }

    /** Gives the slot back; safe to call twice. */
    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        open.decrementAndGet();
      }
    }
  }
}
