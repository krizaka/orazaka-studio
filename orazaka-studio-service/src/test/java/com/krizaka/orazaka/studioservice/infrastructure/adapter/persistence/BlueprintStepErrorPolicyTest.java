package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.jobs.domain.model.FailureCause;
import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.BlueprintStep;
import com.krizaka.orazaka.studio.domain.model.ErrorPolicy;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A step's policy resolved against the declared cause (ADR-053 §5) — the distinction the validation
 * pack had to give up while a refusal and an outage arrived identical.
 */
class BlueprintStepErrorPolicyTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static BlueprintStep parseStep(String stepJson) {
    Blueprint blueprint =
        BlueprintMapperBridge.toBlueprint(
            "s",
            "1.0.0",
            BlueprintStatus.PUBLISHED,
            MAPPER.readTree(
                "{\"studioKey\":\"s\",\"version\":\"1.0.0\",\"steps\":["
                    + stepJson
                    + "],\"outputs\":[]}"),
            "{\"type\":\"object\"}",
            Map.of(),
            10,
            "c",
            Instant.now());
    return blueprint.steps().getFirst();
  }

  private static final String BASE =
      "{\"id\":\"judge\",\"kind\":\"CAPABILITY\",\"featureKey\":\"orazaka.core.chat.completion\","
          + "\"out\":\"judgment\",\"maxAttempts\":1,\"timeout\":\"PT1M\"";

  @Test
  @DisplayName("a step can SKIP an outage and FAIL a refusal — one step, two answers")
  void discriminatesOnTheCause() {
    BlueprintStep step =
        parseStep(BASE + ",\"onError\":\"SKIP\",\"onErrorByCause\":{\"GUARD_REFUSAL\":\"FAIL\"}}");

    assertThat(step.onErrorFor(FailureCause.GUARD_REFUSAL)).isEqualTo(ErrorPolicy.FAIL);
    assertThat(step.onErrorFor(FailureCause.PLATFORM_UNAVAILABLE)).isEqualTo(ErrorPolicy.SKIP);
    assertThat(step.onErrorFor(FailureCause.TIMEOUT)).isEqualTo(ErrorPolicy.SKIP);
  }

  @Test
  @DisplayName("a blueprint that says nothing about causes behaves exactly as it did before")
  void isBackwardCompatible() {
    BlueprintStep step = parseStep(BASE + ",\"onError\":\"SKIP\"}");

    assertThat(step.onErrorByCause()).isEmpty();
    for (FailureCause cause : FailureCause.values()) {
      assertThat(step.onErrorFor(cause)).isEqualTo(ErrorPolicy.SKIP);
    }
    assertThat(step.onErrorFor(null)).isEqualTo(ErrorPolicy.SKIP);
  }

  @Test
  @DisplayName("a misspelt cause is refused, not ignored")
  void refusesACauseNobodyDefined() {
    // A policy silently dropped because GAURD_REFUSAL was misspelt looks exactly like the policy
    // being applied and not helping — which is a debugging session, not an error message.
    assertThatThrownBy(
            () ->
                parseStep(
                    BASE
                        + ",\"onError\":\"SKIP\",\"onErrorByCause\":{\"GAURD_REFUSAL\":\"FAIL\"}}"))
        .hasMessageContaining("GAURD_REFUSAL");
  }
}
