package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.BlueprintStep;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A step's output contract (orazaka-studio#59): read with the same enforced subset as a run
 * output's schema (ADR-076), refused at load on any keyword the saga would not check.
 */
class BlueprintStepOutputContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String BASE =
      "{\"id\":\"rank\",\"kind\":\"CAPABILITY\",\"featureKey\":\"orazaka.core.chat.completion\","
          + "\"out\":\"ranking\",\"onError\":\"RETRY\",\"maxAttempts\":2,\"timeout\":\"PT1M\"";

  private static BlueprintStep parseStep(String stepJson) {
    return BlueprintMapperBridge.toBlueprint(
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
            Instant.now())
        .steps()
        .getFirst();
  }

  @Test
  @DisplayName("a declared contract is kept as JSON text; a step without one has none")
  void readsTheContract() {
    BlueprintStep step =
        parseStep(
            BASE
                + ",\"outputContract\":{\"type\":\"object\",\"required\":[\"items\"],"
                + "\"properties\":{\"items\":{\"type\":\"array\",\"minItems\":1}}}}");

    assertThat(step.outputContract()).contains("\"minItems\":1").contains("\"required\"");
    assertThat(parseStep(BASE + "}").outputContract()).isNull();
  }

  @Test
  @DisplayName("a keyword the saga does not enforce is refused, naming the step")
  void refusesAnUnenforcedKeyword() {
    assertThatThrownBy(
            () ->
                parseStep(
                    BASE
                        + ",\"outputContract\":{\"type\":\"object\",\"properties\":"
                        + "{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z]+$\"}}}}"))
        .hasMessageContaining("rank")
        .hasMessageContaining("pattern");
    assertThatThrownBy(() -> parseStep(BASE + ",\"outputContract\":\"object\"}"))
        .hasMessageContaining("must be an object");
  }
}
