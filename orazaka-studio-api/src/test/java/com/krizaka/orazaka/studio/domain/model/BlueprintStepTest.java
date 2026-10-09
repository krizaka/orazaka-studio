package com.krizaka.orazaka.studio.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlueprintStepTest {

  private static BlueprintStep step(String id) {
    return new BlueprintStep(
        id,
        StepKind.CAPABILITY,
        "orazaka.core.media.vision",
        null,
        Set.of(),
        Map.of("assetId", "{{item}}"),
        "out",
        null,
        0,
        ErrorPolicy.FAIL,
        Map.of(),
        1,
        Duration.ofMinutes(2),
        null);
  }

  @Test
  void accepts_aFullyPopulatedStep() {
    BlueprintStep step =
        new BlueprintStep(
            "describe",
            StepKind.CAPABILITY,
            "orazaka.core.media.vision",
            null,
            Set.of("brief"),
            Map.of("prompt", "Ton: {{config.tone}}"),
            "photoDescriptions",
            "{{inputs.photos}}",
            3,
            ErrorPolicy.SKIP,
            Map.of(),
            2,
            Duration.ofMinutes(2),
            "{{inputs.withPhotos}} == true");

    assertEquals("describe", step.id());
    assertEquals(3, step.maxParallel());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Describe", "describe_photos", "describe photos", "1describe", "-a", ""})
  void rejects_anIdThatIsNotAddressableFromDependsOnAndTheDatabase(String id) {
    assertThrows(IllegalArgumentException.class, () -> step(id));
  }

  @Test
  void rejects_aNullId() {
    assertThrows(IllegalArgumentException.class, () -> step(null));
  }

  @Test
  void rejects_nullKindOrErrorPolicy() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BlueprintStep(
                "a",
                null,
                "k",
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
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new BlueprintStep(
                "a",
                StepKind.CAPABILITY,
                "k",
                null,
                Set.of(),
                Map.of(),
                null,
                null,
                0,
                null,
                Map.of(),
                1,
                Duration.ofMinutes(1),
                null));
  }

  @Test
  void rejects_lessThanOneAttempt_becauseAStepThatNeverRunsIsNotAStep() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BlueprintStep(
                "a",
                StepKind.CAPABILITY,
                "k",
                null,
                Set.of(),
                Map.of(),
                null,
                null,
                0,
                ErrorPolicy.FAIL,
                Map.of(),
                0,
                Duration.ofMinutes(1),
                null));
  }

  @Test
  void rejects_aNegativeConcurrencyCap() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BlueprintStep(
                "a",
                StepKind.CAPABILITY,
                "k",
                null,
                Set.of(),
                Map.of(),
                null,
                null,
                -1,
                ErrorPolicy.FAIL,
                Map.of(),
                1,
                Duration.ofMinutes(1),
                null));
  }

  @Test
  void rejects_aTimeoutThatIsAbsentZeroNegativeOrLongerThanAnHour() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BlueprintStep(
                "a",
                StepKind.CAPABILITY,
                "k",
                null,
                Set.of(),
                Map.of(),
                null,
                null,
                0,
                ErrorPolicy.FAIL,
                Map.of(),
                1,
                null,
                null));
    for (Duration invalid :
        java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(2))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new BlueprintStep(
                  "a",
                  StepKind.CAPABILITY,
                  "k",
                  null,
                  Set.of(),
                  Map.of(),
                  null,
                  null,
                  0,
                  ErrorPolicy.FAIL,
                  Map.of(),
                  1,
                  invalid,
                  null));
    }
  }

  @Test
  void acceptsExactlyAnHour_theDocumentedUpperBound() {
    assertDoesNotThrow(
        () ->
            new BlueprintStep(
                "a",
                StepKind.CAPABILITY,
                "k",
                null,
                Set.of(),
                Map.of(),
                null,
                null,
                0,
                ErrorPolicy.FAIL,
                Map.of(),
                1,
                Duration.ofHours(1),
                null));
  }

  @Test
  void rejects_anUngrammaticalTemplateInAnyOfItsThreeTemplateSlots() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BlueprintStep(
                "a",
                StepKind.CAPABILITY,
                "k",
                null,
                Set.of(),
                Map.of("p", "{{ eval('x') }}"),
                null,
                null,
                0,
                ErrorPolicy.FAIL,
                Map.of(),
                1,
                Duration.ofMinutes(1),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BlueprintStep(
                "a",
                StepKind.CAPABILITY,
                "k",
                null,
                Set.of(),
                Map.of(),
                null,
                "{{ photos }}",
                1,
                ErrorPolicy.FAIL,
                Map.of(),
                1,
                Duration.ofMinutes(1),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BlueprintStep(
                "a",
                StepKind.CAPABILITY,
                "k",
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
                "{{ 1 + 1 }} == 2"));
  }

  @Test
  void copiesDependenciesAndInputs_andTreatsNullAsEmpty() {
    Set<String> dependencies = new HashSet<>(Set.of("brief"));
    BlueprintStep step =
        new BlueprintStep(
            "a",
            StepKind.CAPABILITY,
            "k",
            null,
            dependencies,
            null,
            null,
            null,
            0,
            ErrorPolicy.FAIL,
            Map.of(),
            1,
            Duration.ofMinutes(1),
            null);

    dependencies.add("other");

    assertEquals(Set.of("brief"), step.dependsOn());
    assertTrue(step.inputs().isEmpty());
  }
}
