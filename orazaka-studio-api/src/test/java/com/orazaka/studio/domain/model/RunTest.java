package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunTest {

  private static final UUID RUN = UUID.fromString("9f1c0a10-0000-4000-8000-000000000020");
  private static final UUID INSTALLATION = UUID.fromString("9f1c0a10-0000-4000-8000-000000000010");
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final Instant STARTED = Instant.parse("2026-08-05T10:00:00Z");

  private static Run run(RunStatus status, String holdId, Instant finishedAt) {
    return new Run(
        RUN,
        INSTALLATION,
        ACTOR,
        "trade-showcase",
        "1.0.0",
        status,
        holdId,
        "intention-42",
        Map.of("trade", "plombier"),
        STARTED,
        finishedAt);
  }

  @Test
  void accepts_aRunningRunWithAnOutstandingHold() {
    Run run = run(RunStatus.RUNNING, "hold-7", null);

    assertEquals("hold-7", run.holdId());
    assertNull(run.finishedAt());
  }

  @Test
  void accepts_aRunWithNoHold_becauseAPreviewRunCostsNothing() {
    assertNull(run(RunStatus.RUNNING, null, null).holdId());
  }

  @Test
  void keepsTheBlueprintVersion_soARunStaysReproducibleAfterAnUpgrade() {
    assertEquals("1.0.0", run(RunStatus.SUCCEEDED, "hold-7", STARTED).blueprintVersion());
  }

  @Test
  void rejects_aBlankActorStudioVersionOrCorrelation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Run(
                RUN,
                INSTALLATION,
                " ",
                "k",
                "1.0.0",
                RunStatus.RUNNING,
                null,
                "c",
                Map.of(),
                STARTED,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Run(
                RUN,
                INSTALLATION,
                ACTOR,
                "",
                "1.0.0",
                RunStatus.RUNNING,
                null,
                "c",
                Map.of(),
                STARTED,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Run(
                RUN,
                INSTALLATION,
                ACTOR,
                "k",
                " ",
                RunStatus.RUNNING,
                null,
                "c",
                Map.of(),
                STARTED,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Run(
                RUN,
                INSTALLATION,
                ACTOR,
                "k",
                "1.0.0",
                RunStatus.RUNNING,
                null,
                "",
                Map.of(),
                STARTED,
                null));
  }

  @Test
  void rejects_aMissingIdentityStatusOrStartTime() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Run(
                null,
                INSTALLATION,
                ACTOR,
                "k",
                "1.0.0",
                RunStatus.RUNNING,
                null,
                "c",
                Map.of(),
                STARTED,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new Run(
                RUN,
                null,
                ACTOR,
                "k",
                "1.0.0",
                RunStatus.RUNNING,
                null,
                "c",
                Map.of(),
                STARTED,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new Run(
                RUN, INSTALLATION, ACTOR, "k", "1.0.0", null, null, "c", Map.of(), STARTED, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new Run(
                RUN,
                INSTALLATION,
                ACTOR,
                "k",
                "1.0.0",
                RunStatus.RUNNING,
                null,
                "c",
                Map.of(),
                null,
                null));
  }

  @Test
  void copiesTheInputs_andTreatsNullAsEmpty() {
    Map<String, Object> source = new HashMap<>(Map.of("trade", "plombier"));
    Run run =
        new Run(
            RUN,
            INSTALLATION,
            ACTOR,
            "k",
            "1.0.0",
            RunStatus.RUNNING,
            null,
            "c",
            source,
            STARTED,
            null);

    source.put("trade", "électricien");

    assertEquals("plombier", run.inputs().get("trade"));
    assertTrue(
        new Run(
                RUN,
                INSTALLATION,
                ACTOR,
                "k",
                "1.0.0",
                RunStatus.RUNNING,
                null,
                "c",
                null,
                STARTED,
                null)
            .inputs()
            .isEmpty());
  }
}
