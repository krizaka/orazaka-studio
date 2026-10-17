package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.studioservice.domain.model.ModelChoice;
import com.krizaka.orazaka.studioservice.domain.port.ModelOptionsClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The form contract: a field fed by the catalogue arrives with its choices; a stray value is
 * refused.
 */
class FormSchemaServiceTest {

  private static final String SCHEMA =
      """
      {"type":"object","required":["prompt"],"properties":{
        "prompt":{"type":"string","title":"Prompt"},
        "size":{"type":"string","enum":["1024x1024","512x512"],"default":"1024x1024"},
        "model":{"type":"string","maxLength":120,"title":"Model",
                 "x-options":{"source":"models","category":"image"}}}}
      """;

  private static final ModelChoice FLUX =
      new ModelChoice(
          "flux-2-klein-4b",
          "FLUX.2 [klein] 4B",
          "localai-image",
          "local",
          "balanced",
          62,
          null,
          "Local default.",
          false,
          "FLUX.2 [klein] 4B is not running (its engine could not be reached)",
          false);
  private static final ModelChoice SD35 =
      new ModelChoice(
          "stable-diffusion-3.5-large",
          "Stable Diffusion 3.5 Large",
          "do-inference-images",
          "cloud",
          "fast",
          8,
          8,
          "Hosted.",
          true,
          null,
          true);

  private final ObjectMapper json = new ObjectMapper();

  private FormSchemaService service(ModelOptionsClient client) {
    return new FormSchemaService(client, json);
  }

  private static ModelOptionsClient catalogue(ModelChoice... choices) {
    return category -> "image".equals(category) ? Optional.of(List.of(choices)) : Optional.empty();
  }

  @Test
  @DisplayName("A model field arrives as a labelled choice list with the facts a picker shows")
  void modelFieldCarriesItsChoices() {
    JsonNode model =
        json.readTree(service(catalogue(SD35, FLUX)).forForm(SCHEMA))
            .path("properties")
            .path("model");

    assertThat(model.path("oneOf")).hasSize(2);
    JsonNode first = model.path("oneOf").get(0);
    assertThat(first.path("const").asString()).isEqualTo("stable-diffusion-3.5-large");
    assertThat(first.path("title").asString()).isEqualTo("Stable Diffusion 3.5 Large");
    assertThat(first.path("description").asString()).isEqualTo("Hosted.");
    assertThat(first.path("x-meta").path("runsOn").asString()).isEqualTo("cloud");
    assertThat(first.path("x-meta").path("speed").asString()).isEqualTo("fast");
    assertThat(first.path("x-meta").path("typicalSeconds").asInt()).isEqualTo(8);
    assertThat(first.path("x-meta").path("priceCents").asInt()).isEqualTo(8);
    assertThat(first.path("x-meta").path("available").asBoolean()).isTrue();
    JsonNode second = model.path("oneOf").get(1);
    assertThat(second.path("x-meta").path("available").asBoolean()).isFalse();
    assertThat(second.path("x-meta").path("unavailableReason").asString()).contains("FLUX.2");
    assertThat(second.path("x-meta").has("priceCents")).as("free: no price").isFalse();
  }

  @Test
  @DisplayName("The default that will run is the field's default — the catalogue's, resolved")
  void defaultIsTheCataloguesResolvedDefault() {
    JsonNode model =
        json.readTree(service(catalogue(SD35, FLUX)).forForm(SCHEMA))
            .path("properties")
            .path("model");

    assertThat(model.path("default").asString()).isEqualTo("stable-diffusion-3.5-large");
  }

  @Test
  @DisplayName(
      "Fields that are not fed by the catalogue are left exactly as the blueprint wrote them")
  void otherFieldsAreUntouched() {
    JsonNode props = json.readTree(service(catalogue(SD35)).forForm(SCHEMA)).path("properties");

    assertThat(props.path("size").path("enum")).hasSize(2);
    assertThat(props.path("size").path("default").asString()).isEqualTo("1024x1024");
    assertThat(props.path("prompt").has("oneOf")).isFalse();
  }

  @Test
  @DisplayName(
      "When the catalogue cannot be reached the schema is returned as written, never an empty menu")
  void unreachableCatalogueLeavesTheDirective() {
    String form = service(category -> Optional.empty()).forForm(SCHEMA);

    JsonNode model = json.readTree(form).path("properties").path("model");
    assertThat(model.has("oneOf")).isFalse();
    assertThat(model.path("x-options").path("category").asString()).isEqualTo("image");
  }

  @Test
  @DisplayName("A blank or non-JSON schema passes through untouched")
  void nonSchemasPassThrough() {
    FormSchemaService service = service(catalogue(SD35));

    assertThat(service.forForm(null)).isNull();
    assertThat(service.forForm(" ")).isEqualTo(" ");
    assertThat(service.forForm("not json")).isEqualTo("not json");
  }

  @Test
  @DisplayName("A typed model id that does not exist is refused naming the models that do")
  void unknownModelIsRefused() {
    FormSchemaService service = service(catalogue(SD35, FLUX));

    assertThatThrownBy(() -> service.requireClosedSets(SCHEMA, Map.of("model", "sd-9000")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'sd-9000' is not a image model")
        .hasMessageContaining("stable-diffusion-3.5-large");
  }

  @Test
  @DisplayName(
      "A real model whose engine is off is refused with the reason, before a credit is held")
  void unavailableModelIsRefusedWithItsReason() {
    FormSchemaService service = service(catalogue(SD35, FLUX));

    assertThatThrownBy(() -> service.requireClosedSets(SCHEMA, Map.of("model", "flux-2-klein-4b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is not running");
  }

  @Test
  @DisplayName("An available model, a blank model and an absent model are accepted")
  void validAndBlankValuesPass() {
    FormSchemaService service = service(catalogue(SD35, FLUX));

    assertThatCode(
            () -> {
              service.requireClosedSets(SCHEMA, Map.of("model", "stable-diffusion-3.5-large"));
              service.requireClosedSets(SCHEMA, Map.of("model", " "));
              service.requireClosedSets(SCHEMA, Map.of("prompt", "a cat"));
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("A static enum is closed too: a format that was never offered is refused")
  void staticEnumIsClosed() {
    FormSchemaService service = service(catalogue(SD35));

    assertThatThrownBy(() -> service.requireClosedSets(SCHEMA, Map.of("size", "999x999")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size: '999x999' is not one of 1024x1024, 512x512");
    assertThatCode(() -> service.requireClosedSets(SCHEMA, Map.of("size", "512x512")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("A labelled oneOf of consts is closed like an enum")
  void labelledChoicesAreClosed() {
    String schema =
        """
        {"properties":{"aspect":{"type":"string","oneOf":[
          {"const":"9:16","title":"Vertical"},{"const":"16:9","title":"Wide"}]}}}
        """;

    assertThatThrownBy(
            () -> service(catalogue()).requireClosedSets(schema, Map.of("aspect", "4:3")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("aspect: '4:3' is not one of 9:16, 16:9");
  }

  @Test
  @DisplayName(
      "With the catalogue unreachable the model is not judged here: the executor re-checks")
  void unreachableCatalogueDoesNotBlockTheRun() {
    assertThatCode(
            () ->
                service(category -> Optional.empty())
                    .requireClosedSets(SCHEMA, Map.of("model", "anything")))
        .doesNotThrowAnyException();
  }
}
