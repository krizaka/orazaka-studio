package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.TooManyRunStreamsException;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import java.io.IOException;
import java.time.Clock;
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
     * @param id the event number on this connection, from 1
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
  private final Clock clock;
  private final Sleeper sleeper;
  private final Map<String, AtomicInteger> openByActor = new ConcurrentHashMap<>();

  @Autowired
  public RunEventService(StudioRunService runs, StudioRuntimeConfigService config) {
    this(runs, config, Clock.systemUTC(), Thread::sleep);
  }

  RunEventService(
      StudioRunService runs, StudioRuntimeConfigService config, Clock clock, Sleeper sleeper) {
    this.runs = Objects.requireNonNull(runs, "StudioRunService cannot be null");
    this.config = Objects.requireNonNull(config, "StudioRuntimeConfigService cannot be null");
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
    runs.find(runId, actorId).orElseThrow(() -> new RunNotFoundException(runId));
    int limit = Math.max(1, config.intValue(MAX_STREAMS_KEY, 5));
    AtomicInteger open = openByActor.computeIfAbsent(actorId, k -> new AtomicInteger());
    if (open.incrementAndGet() > limit) {
      open.decrementAndGet();
      throw new TooManyRunStreamsException(limit);
    }
    return new Subscription(runId, actorId, open);
  }

  /** One open stream. */
  public final class Subscription implements AutoCloseable {

    private final UUID runId;
    private final String actorId;
    private final AtomicInteger open;
    private boolean closed;

    private Subscription(UUID runId, String actorId, AtomicInteger open) {
      this.runId = runId;
      this.actorId = actorId;
      this.open = open;
    }

    /**
     * Sends the run's snapshots until it is over, the client leaves or the stream's time is up.
     * Blocks; run it on its own (virtual) thread.
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
      RunDetail last = null;
      while (sink.open() && clock.millis() < deadline) {
        RunDetail run = runs.find(runId, actorId).orElse(null);
        if (run == null) {
          return;
        }
        if (!run.equals(last)) {
          id++;
          last = run;
          lastSent = clock.millis();
          if (TERMINAL.contains(run.status())) {
            sink.done(id, run);
            return;
          }
          sink.snapshot(id, run);
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
}
