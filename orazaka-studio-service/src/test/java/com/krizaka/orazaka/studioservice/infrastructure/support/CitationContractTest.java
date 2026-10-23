package com.krizaka.orazaka.studioservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.BlueprintStep;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence.BlueprintMapperBridge;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The citation shape of ADR-087 as the output contract of {@code orazaka.core.research.grounded}
 * (orazaka-studio#77): fully enforceable by the saga, applied to a grounded step that declares no
 * contract of its own, and naming paths and rules — never values — when an output breaks it.
 */
class CitationContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final JsonNode SCHEMA = JSON.readTree(CitationContract.SCHEMA);

  private static BlueprintStep parseStep(String featureKey, String extra) {
    return BlueprintMapperBridge.toBlueprint(
            "s",
            "1.0.0",
            BlueprintStatus.PUBLISHED,
            MAPPER.readTree(
                "{\"studioKey\":\"s\",\"version\":\"1.0.0\",\"steps\":[{\"id\":\"ground\","
                    + "\"kind\":\"CAPABILITY\",\"featureKey\":\""
                    + featureKey
                    + "\",\"out\":\"ground\",\"onError\":\"FAIL\",\"maxAttempts\":1,"
                    + "\"timeout\":\"PT1M\""
                    + extra
                    + "}],\"outputs\":[]}"),
            "{\"type\":\"object\"}",
            Map.of(),
            10,
            "c",
            Instant.now())
        .steps()
        .getFirst();
  }

  @Test
  @DisplayName("every keyword of the contract is one the saga enforces")
  void isFullyEnforceable() {
    assertThat(OutputContract.unsupportedKeywords(SCHEMA)).isEmpty();
  }

  @Test
  @DisplayName("a grounded capability step that declares no contract is held to it; others are not")
  void appliesToAGroundedStepWithoutItsOwn() {
    assertThat(CitationContract.defaultFor(parseStep(CitationContract.FEATURE_KEY, "")))
        .contains(CitationContract.SCHEMA);
    assertThat(CitationContract.defaultFor(parseStep("orazaka.core.chat.completion", "")))
        .isEmpty();
    // A declared contract is the step's own business: the saga prefers it (RunSagaService).
    BlueprintStep declared =
        parseStep(
            CitationContract.FEATURE_KEY,
            ",\"outputContract\":{\"type\":\"object\",\"required\":[\"answer\"]}");
    assertThat(declared.outputContract()).contains("\"required\":[\"answer\"]");
  }

  @Test
  @DisplayName("a grounded answer in the shape passes; an answer with no claims is a valid answer")
  void aConformingAnswerPasses() {
    JsonNode answer =
        JSON.readTree(
            """
            {"answer":"Trente jours.",
             "claims":[{"text":"Trente jours.","status":"VERIFIED",
               "citations":[{"url":"https://example.org/r","quote":"trente jours",
                 "contentHash":"%s","fetchedAt":"2026-10-23T12:00:00Z"}]},
               {"text":"Quatorze jours.","status":"TO_VALIDATE","reason":"QUOTE_NOT_FOUND",
                "citations":[]}],
             "sources":[{"url":"https://example.org/r","title":"R","sourceKey":"site"}]}
            """
                .formatted("a".repeat(64)));
    assertThat(OutputContract.violations(SCHEMA, answer)).isEmpty();
    assertThat(
            OutputContract.violations(
                SCHEMA, JSON.readTree("{\"answer\":\"\",\"claims\":[],\"sources\":[]}")))
        .isEmpty();
  }

  @Test
  @DisplayName("an output outside the shape is named by path and rule, never by its value")
  void aBrokenAnswerIsNamedByPath() {
    JsonNode broken =
        JSON.readTree(
            """
            {"answer":"Quatorze jours.",
             "claims":[{"text":"Quatorze jours.","status":"MAYBE",
               "citations":[{"url":"x","quote":"%s","contentHash":"short","fetchedAt":"t"}]}]}
            """
                .formatted("q".repeat(301)));
    assertThat(OutputContract.violations(SCHEMA, broken))
        .containsExactlyInAnyOrder(
            "$.sources: required",
            "$.claims[0].status: must be one of the declared values",
            "$.claims[0].citations[0].url: minLength 8",
            "$.claims[0].citations[0].quote: maxLength 300",
            "$.claims[0].citations[0].contentHash: minLength 64")
        .noneMatch(line -> line.contains("Quatorze"));
  }
}
