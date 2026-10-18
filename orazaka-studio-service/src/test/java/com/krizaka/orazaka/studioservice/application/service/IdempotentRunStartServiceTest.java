package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studioservice.domain.exception.IdempotencyKeyReusedException;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.studioservice.domain.port.RunIdempotencyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same {@code Idempotency-Key} starts one run, however many times the request arrives
 * (orazaka#189 follow-up, ADR-083): a client whose connection dropped after the engine accepted the
 * run can retry without paying twice.
 */
class IdempotentRunStartServiceTest {

  private static final String ACTOR = "actor-1";
  private static final String SCOPE = "studio:compliance-check";

  private final InMemoryStore store = new InMemoryStore();
  private final StudioRunService runs = mock(StudioRunService.class);
  private final AtomicInteger started = new AtomicInteger();
  private IdempotentRunStartService starter;
  private final UUID firstRun = UUID.randomUUID();
  private final RunDetail first = mock(RunDetail.class);

  @BeforeEach
  void wire() {
    starter = new IdempotentRunStartService(store, runs);
    when(first.id()).thenReturn(firstRun);
    when(runs.find(any(), any())).thenReturn(Optional.of(first));
  }

  private IdempotentRunStartService.Started start(String key, Map<String, Object> inputs) {
    return starter.start(
        SCOPE,
        ACTOR,
        inputs,
        key,
        () -> {
          started.incrementAndGet();
          return first;
        });
  }

  @Test
  @DisplayName("without a key every call starts its own run, as before")
  void noKeyNoDeduplication() {
    start(null, Map.of("a", 1));
    start(null, Map.of("a", 1));
    start("  ", Map.of("a", 1));

    assertThat(started).hasValue(3);
    assertThat(store.recorded).isEmpty();
  }

  @Test
  @DisplayName("the same key and the same request answer the same run, started once")
  void sameKeySameRunStartedOnce() {
    IdempotentRunStartService.Started one = start("k-1", Map.of("document", "lease"));
    IdempotentRunStartService.Started two = start("k-1", Map.of("document", "lease"));

    assertThat(started).hasValue(1);
    assertThat(one.replayed()).isFalse();
    assertThat(two.replayed()).isTrue();
    assertThat(two.run().id()).isEqualTo(firstRun);
  }

  @Test
  @DisplayName("the order of the input fields does not make a different request")
  void fieldOrderIsNotARequestChange() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("x", 1);
    a.put("y", List.of("p", Map.of("m", 1, "n", 2)));
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("y", List.of("p", Map.of("n", 2, "m", 1)));
    b.put("x", 1);

    start("k-2", a);
    IdempotentRunStartService.Started again = start("k-2", b);

    assertThat(again.replayed()).isTrue();
    assertThat(started).hasValue(1);
  }

  @Test
  @DisplayName("the same key with another request is refused, never answered with the first run")
  void keyReusedForAnotherRequest() {
    start("k-3", Map.of("document", "lease"));

    assertThatThrownBy(() -> start("k-3", Map.of("document", "other")))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    assertThat(started).hasValue(1);
  }

  @Test
  @DisplayName("the same key on another Studio is another request")
  void keyReusedOnAnotherStudio() {
    start("k-4", Map.of());

    assertThatThrownBy(() -> starter.start("studio:other", ACTOR, Map.of(), "k-4", () -> first))
        .isInstanceOf(IdempotencyKeyReusedException.class);
  }

  @Test
  @DisplayName("a key belongs to its actor: another actor with the same key starts its own run")
  void keysAreScopedToTheActor() {
    start("k-5", Map.of());
    starter.start(
        SCOPE,
        "actor-2",
        Map.of(),
        "k-5",
        () -> {
          started.incrementAndGet();
          return first;
        });

    assertThat(started).hasValue(2);
  }

  @Test
  @DisplayName(
      "the key is locked before it is looked up, so two parallel requests cannot both start")
  void lockedBeforeLookup() {
    start("k-6", Map.of());

    assertThat(store.calls.subList(0, 3)).containsExactly("lock", "find", "record");
  }

  @Test
  @DisplayName("a failed start records nothing: the retry with the same key starts for real")
  void failedStartIsNotRemembered() {
    assertThatThrownBy(
            () ->
                starter.start(
                    SCOPE,
                    ACTOR,
                    Map.of(),
                    "k-7",
                    () -> {
                      throw new IllegalStateException("too many concurrent runs");
                    }))
        .isInstanceOf(IllegalStateException.class);

    IdempotentRunStartService.Started retry = start("k-7", Map.of());

    assertThat(retry.replayed()).isFalse();
    assertThat(started).hasValue(1);
  }

  @Test
  @DisplayName("a key must be 1 to 255 visible ASCII characters")
  void invalidKeysAreRefused() {
    assertThatThrownBy(() -> start("x".repeat(256), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> start("with space", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> start("accentué", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(started).hasValue(0);
  }

  /** The store's contract, in memory: one row per (actor, key). */
  private static final class InMemoryStore implements RunIdempotencyStore {
    final Map<String, Recorded> recorded = new java.util.HashMap<>();
    final List<String> calls = new java.util.ArrayList<>();

    @Override
    public void lock(String actorId, String key) {
      calls.add("lock");
    }

    @Override
    public Optional<Recorded> find(String actorId, String key) {
      calls.add("find");
      return Optional.ofNullable(recorded.get(actorId + "|" + key));
    }

    @Override
    public void record(UUID runId, String actorId, String key, String fingerprint) {
      calls.add("record");
      recorded.put(actorId + "|" + key, new Recorded(runId, fingerprint));
    }
  }
}
