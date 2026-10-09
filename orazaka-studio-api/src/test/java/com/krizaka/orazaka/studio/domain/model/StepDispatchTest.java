package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StepDispatchTest {

  private static final UUID RUN = UUID.fromString("9f1c0a10-0000-4000-8000-000000000020");
  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  private static StepDispatch dispatch(String featureKey) {
    return new StepDispatch(
        RUN,
        "describe",
        0,
        featureKey,
        null,
        Map.of("assetId", "a1"),
        ACTOR,
        "intention-42",
        Map.of("tone", "premium"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD);
  }

  @Test
  void accepts_aFullyResolvedDispatch() {
    StepDispatch dispatch = dispatch("orazaka.core.media.vision");

    assertEquals("orazaka.core.media.vision", dispatch.featureKey());
    assertEquals("a1", dispatch.resolvedInputs().get("assetId"));
  }

  @Test
  void carriesNoHold_soAStepCannotSettleTheRun() {
    // [ADR-041] A dispatch carrying the run's hold made every step's outcome look billable to the
    // billing consumer: the first to finish settled the whole run at the AGENT/CALL rate and the
    // rest were free. The field is gone rather than nulled, because a field that must always be
    // null is a trap the next author falls into.
    assertEquals(
        0,
        java.util.Arrays.stream(StepDispatch.class.getRecordComponents())
            .filter(
                component ->
                    component.getName().toLowerCase(java.util.Locale.ROOT).contains("hold"))
            .count(),
        "StepDispatch must carry no hold of any kind");
  }

  @Test
  void rejects_aDispatchWithNothingToRouteOn() {
    assertThrows(IllegalArgumentException.class, () -> dispatch(" "));
    assertThrows(IllegalArgumentException.class, () -> dispatch(null));
  }

  @Test
  void rejects_aBlankStepIdActorOrCorrelation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepDispatch(
                RUN,
                " ",
                0,
                "k",
                null,
                Map.of(),
                ACTOR,
                "c",
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepDispatch(
                RUN,
                "a",
                0,
                "k",
                null,
                Map.of(),
                "",
                "c",
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepDispatch(
                RUN,
                "a",
                0,
                "k",
                null,
                Map.of(),
                ACTOR,
                " ",
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD));
  }

  @Test
  void rejects_aNegativeOrdinalOrAMissingRun() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepDispatch(
                RUN,
                "a",
                -1,
                "k",
                null,
                Map.of(),
                ACTOR,
                "c",
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD));
    assertThrows(
        NullPointerException.class,
        () ->
            new StepDispatch(
                null,
                "a",
                0,
                "k",
                null,
                Map.of(),
                ACTOR,
                "c",
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD));
  }

  @Test
  void copiesTheBrandContext_soAnInstallationReconfiguredMidRunCannotChangeIt() {
    Map<String, String> brand = new java.util.HashMap<>(Map.of("tone", "premium"));
    StepDispatch dispatch =
        new StepDispatch(
            RUN,
            "a",
            0,
            "k",
            null,
            Map.of(),
            ACTOR,
            "c",
            brand,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD);

    brand.put("tone", "direct");

    assertEquals("premium", dispatch.brandContext().get("tone"));
    assertTrue(
        new StepDispatch(
                RUN,
                "a",
                0,
                "k",
                null,
                Map.of(),
                ACTOR,
                "c",
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD)
            .brandContext()
            .isEmpty());
  }

  @Test
  void copiesTheResolvedInputs_andTreatsNullAsEmpty() {
    Map<String, Object> source = new HashMap<>(Map.of("assetId", "a1"));
    StepDispatch dispatch =
        new StepDispatch(
            RUN,
            "a",
            0,
            "k",
            null,
            source,
            ACTOR,
            "c",
            Map.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD);

    source.put("assetId", "tampered");

    assertEquals("a1", dispatch.resolvedInputs().get("assetId"));
    assertTrue(
        new StepDispatch(
                RUN,
                "a",
                0,
                "k",
                null,
                null,
                ACTOR,
                "c",
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD)
            .resolvedInputs()
            .isEmpty());
  }

  @Test
  @DisplayName("A dispatch must name exactly one plane — neither would be awaited forever")
  void namesExactlyOneTarget() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepDispatch(
                RUN,
                "a",
                0,
                null,
                null,
                Map.of(),
                ACTOR,
                "c",
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepDispatch(
                RUN,
                "a",
                0,
                "k",
                "INSTAGRAM",
                Map.of(),
                ACTOR,
                "c",
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD));
  }

  @Test
  @DisplayName("A connector dispatch names its connector instead of a capability")
  void connectorDispatchIsValid() {
    StepDispatch connector =
        new StepDispatch(
            RUN,
            "publish",
            0,
            null,
            "INSTAGRAM",
            Map.of(),
            ACTOR,
            "c",
            Map.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD);

    assertEquals("INSTAGRAM", connector.connectorType());
    assertNull(connector.featureKey());
  }
}
