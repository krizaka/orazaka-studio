package com.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.studio.domain.exception.BlueprintValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlueprintTest {

  private static final String SCHEMA = "{\"type\":\"object\"}";

  private static BlueprintStep step(String id, Set<String> dependsOn, String out) {
    return new BlueprintStep(
        id,
        StepKind.CAPABILITY,
        "orazaka.core.media.vision",
        null,
        dependsOn,
        Map.of(),
        out,
        null,
        0,
        ErrorPolicy.FAIL,
        Map.of(),
        1,
        Duration.ofMinutes(2),
        null);
  }

  private static Blueprint of(List<BlueprintStep> steps) {
    return new Blueprint(
        "trade-showcase",
        "1.0.0",
        BlueprintStatus.PUBLISHED,
        SCHEMA,
        steps,
        List.of(),
        Map.of(),
        80,
        "initial",
        Instant.parse("2026-08-05T10:00:00Z"));
  }

  @Test
  void accepts_aValidChain() {
    Blueprint blueprint =
        of(
            List.of(
                step("describe", Set.of(), "photoDescriptions"),
                step("showcase", Set.of("describe"), "showcaseImage")));

    assertEquals(2, blueprint.steps().size());
    assertEquals("trade-showcase", blueprint.studioKey());
  }

  @Test
  void accepts_aDiamond_becauseADagIsNotAChain() {
    assertDoesNotThrow(
        () ->
            of(
                List.of(
                    step("root", Set.of(), "a"),
                    step("left", Set.of("root"), "b"),
                    step("right", Set.of("root"), "c"),
                    step("join", Set.of("left", "right"), "d"))));
  }

  @Test
  void rejects_anEmptyStepList() {
    BlueprintValidationException thrown =
        assertThrows(BlueprintValidationException.class, () -> of(List.of()));

    assertNull(thrown.stepId());
  }

  @Test
  void rejects_aNullStepList() {
    assertThrows(BlueprintValidationException.class, () -> of(null));
  }

  @Test
  void rejects_duplicateStepIds_becauseDependsOnWouldBeAmbiguous() {
    BlueprintValidationException thrown =
        assertThrows(
            BlueprintValidationException.class,
            () -> of(List.of(step("describe", Set.of(), "a"), step("describe", Set.of(), "b"))));

    assertEquals("describe", thrown.stepId());
  }

  @Test
  void rejects_aDanglingDependency_namingTheOffendingStep() {
    BlueprintValidationException thrown =
        assertThrows(
            BlueprintValidationException.class,
            () -> of(List.of(step("showcase", Set.of("describe"), "a"))));

    assertEquals("showcase", thrown.stepId());
    assertTrue(thrown.getMessage().contains("describe"));
  }

  @Test
  void rejects_aTwoNodeCycle() {
    BlueprintValidationException thrown =
        assertThrows(
            BlueprintValidationException.class,
            () -> of(List.of(step("a", Set.of("b"), "x"), step("b", Set.of("a"), "y"))));

    assertTrue(thrown.getMessage().contains("cyclic"));
  }

  @Test
  void rejects_aSelfDependency() {
    assertThrows(
        BlueprintValidationException.class, () -> of(List.of(step("a", Set.of("a"), "x"))));
  }

  @Test
  void rejects_aCycleReachableOnlyFromAValidPrefix() {
    // The trap a naive "every dependency resolves" check misses: the graph is well-formed up to
    // `b`, and only the b↔c pair never becomes ready.
    assertThrows(
        BlueprintValidationException.class,
        () ->
            of(
                List.of(
                    step("a", Set.of(), "x"),
                    step("b", Set.of("a", "c"), "y"),
                    step("c", Set.of("b"), "z"))));
  }

  @Test
  void rejects_twoStepsPublishingTheSameOutName() {
    BlueprintValidationException thrown =
        assertThrows(
            BlueprintValidationException.class,
            () -> of(List.of(step("a", Set.of(), "shared"), step("b", Set.of(), "shared"))));

    assertEquals("b", thrown.stepId());
  }

  @Test
  void acceptsSeveralStepsWithNoOutName() {
    assertDoesNotThrow(() -> of(List.of(step("a", Set.of(), null), step("b", Set.of(), null))));
  }

  @Test
  void rejects_aCapabilityStepWithNoFeatureKey() {
    BlueprintStep unrouted =
        new BlueprintStep(
            "orphan",
            StepKind.CAPABILITY,
            "  ",
            null,
            Set.of(),
            Map.of(),
            "x",
            null,
            0,
            ErrorPolicy.FAIL,
            Map.of(),
            1,
            Duration.ofMinutes(1),
            null);

    BlueprintValidationException thrown =
        assertThrows(BlueprintValidationException.class, () -> of(List.of(unrouted)));

    assertEquals("orphan", thrown.stepId());
  }

  @Test
  void acceptsANonCapabilityStepWithNoFeatureKey() {
    BlueprintStep approval =
        new BlueprintStep(
            "approve",
            StepKind.APPROVAL,
            null,
            null,
            Set.of(),
            Map.of(),
            null,
            null,
            0,
            ErrorPolicy.FAIL,
            Map.of(),
            1,
            Duration.ofMinutes(1),
            null);

    assertDoesNotThrow(() -> of(List.of(approval)));
  }

  @Test
  void rejects_anUncappedFanOut_becauseTheAcceleratorIsTheScarceResource() {
    BlueprintStep uncapped =
        new BlueprintStep(
            "describe",
            StepKind.CAPABILITY,
            "orazaka.core.media.vision",
            null,
            Set.of(),
            Map.of("assetId", "{{item}}"),
            "out",
            "{{inputs.photos}}",
            0,
            ErrorPolicy.SKIP,
            Map.of(),
            1,
            Duration.ofMinutes(2),
            null);

    BlueprintValidationException thrown =
        assertThrows(BlueprintValidationException.class, () -> of(List.of(uncapped)));

    assertEquals("describe", thrown.stepId());
  }

  @Test
  void rejects_blankIdentityFieldsAndANegativeEstimate() {
    List<BlueprintStep> steps = List.of(step("a", Set.of(), "x"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Blueprint(
                " ",
                "1.0.0",
                BlueprintStatus.DRAFT,
                SCHEMA,
                steps,
                List.of(),
                Map.of(),
                0,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Blueprint(
                "k", "", BlueprintStatus.DRAFT, SCHEMA, steps, List.of(), Map.of(), 0, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Blueprint(
                "k",
                "1.0.0",
                BlueprintStatus.DRAFT,
                " ",
                steps,
                List.of(),
                Map.of(),
                0,
                null,
                null));
    assertThrows(
        NullPointerException.class,
        () -> new Blueprint("k", "1.0.0", null, SCHEMA, steps, List.of(), Map.of(), 0, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Blueprint(
                "k",
                "1.0.0",
                BlueprintStatus.DRAFT,
                SCHEMA,
                steps,
                List.of(),
                Map.of(),
                -1,
                null,
                null));
  }

  @Test
  void treatsNullOutputsAndDefaultsAsEmpty_soNoCallerNullChecksThem() {
    Blueprint blueprint =
        new Blueprint(
            "k",
            "1.0.0",
            BlueprintStatus.DRAFT,
            SCHEMA,
            List.of(step("a", Set.of(), "x")),
            null,
            null,
            0,
            null,
            null);

    assertTrue(blueprint.outputs().isEmpty());
    assertTrue(blueprint.configSchemaDefaults().isEmpty());
    assertNull(blueprint.publishedAt());
  }

  @Test
  void rejects_aConnectorStepThatNamesNoConnector() {
    BlueprintStep unrouted =
        new BlueprintStep(
            "publish",
            StepKind.CONNECTOR,
            null,
            null,
            Set.of(),
            Map.of(),
            null,
            null,
            0,
            ErrorPolicy.SKIP,
            Map.of(),
            1,
            Duration.ofMinutes(1),
            null);

    BlueprintValidationException thrown =
        assertThrows(BlueprintValidationException.class, () -> of(List.of(unrouted)));

    assertEquals("publish", thrown.stepId());
  }

  @Test
  void acceptsAConnectorStepThatNamesItsConnector() {
    BlueprintStep publish =
        new BlueprintStep(
            "publish",
            StepKind.CONNECTOR,
            null,
            "INSTAGRAM",
            Set.of(),
            Map.of("media", "{{inputs.photos}}"),
            null,
            null,
            0,
            ErrorPolicy.SKIP,
            Map.of(),
            1,
            Duration.ofMinutes(1),
            null);

    assertDoesNotThrow(() -> of(List.of(publish)));
  }
}
