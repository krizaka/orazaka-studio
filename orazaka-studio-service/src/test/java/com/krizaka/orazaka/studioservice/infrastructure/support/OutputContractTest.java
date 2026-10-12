package com.krizaka.orazaka.studioservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OutputContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The session-summary contract the wellbeing pack declares (ADR-076). */
  private static final JsonNode SUMMARY =
      JSON.readTree(
          """
          {"type":"object","additionalProperties":false,
           "required":["themes","whatHelped","smallStep","bringToTherapist","locale"],
           "properties":{
             "themes":{"type":"array","maxItems":3,"items":{"type":"string","maxLength":200}},
             "whatHelped":{"type":"string"},
             "smallStep":{"type":"string"},
             "bringToTherapist":{"type":"array","items":{"type":"string"}},
             "locale":{"type":"string","enum":["fr-CA","fr-FR","en"]}}}
          """);

  @Test
  @DisplayName("A conforming summary passes")
  void conforms() {
    JsonNode value =
        JSON.readTree(
            """
            {"themes":["travail","enfants"],"whatHelped":"en parler","smallStep":"jeudi soir",
             "bringToTherapist":["la fatigue"],"locale":"fr-CA"}
            """);

    assertThat(OutputContract.violations(SUMMARY, value)).isEmpty();
  }

  @Test
  @DisplayName("Each broken rule is named by its path, never by the value")
  void namesPathsNotValues() {
    JsonNode value =
        JSON.readTree(
            """
            {"themes":["a","b","c","d"],"whatHelped":3,"bringToTherapist":[],
             "locale":"de","mood":"triste"}
            """);

    assertThat(OutputContract.violations(SUMMARY, value))
        .containsExactlyInAnyOrder(
            "$.smallStep: required",
            "$.themes: maxItems 3",
            "$.whatHelped: must be string",
            "$.locale: must be one of the declared values",
            "$.mood: not declared")
        .noneMatch(line -> line.contains("triste"));
  }

  @Test
  @DisplayName("A keyword the checker would not enforce is reported, at any depth")
  void refusesWhatItCannotEnforce() {
    JsonNode schema =
        JSON.readTree(
            """
            {"type":"object","properties":{"a":{"type":"string","pattern":"^x"}},"oneOf":[]}
            """);

    assertThat(OutputContract.unsupportedKeywords(schema))
        .containsExactlyInAnyOrder("oneOf", "pattern");
    assertThat(OutputContract.unsupportedKeywords(SUMMARY)).isEmpty();
  }

  @Test
  @DisplayName("A fence around the whole answer is packaging; text around the object is not")
  void unfencesOnlyAWholeFence() {
    assertThat(OutputContract.unfenced("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    assertThat(OutputContract.unfenced("  {\"a\":1} ")).isEqualTo("{\"a\":1}");
    assertThat(OutputContract.unfenced("Voici :\n```json\n{}\n```"))
        .isEqualTo("Voici :\n```json\n{}\n```");
  }

  @Test
  @DisplayName("Bounds on numbers and strings hold")
  void bounds() {
    JsonNode schema =
        JSON.readTree(
            """
            {"type":"object","properties":{"n":{"type":"integer","minimum":1,"maximum":5},
             "s":{"type":"string","minLength":2}}}
            """);

    assertThat(OutputContract.violations(schema, JSON.readTree("{\"n\":6,\"s\":\"x\"}")))
        .containsExactlyInAnyOrder("$.n: above maximum", "$.s: minLength 2");
    assertThat(OutputContract.violations(schema, JSON.readTree("{\"n\":0}")))
        .containsExactly("$.n: below minimum");
  }
}
