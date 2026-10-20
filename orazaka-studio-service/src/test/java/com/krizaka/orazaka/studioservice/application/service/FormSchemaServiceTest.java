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
          "gpu_offline",
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
  @DisplayName("A category written in capitals still reaches the catalogue's lowercase one")
  void categoryCaseDoesNotMatter() {
    String shouting = SCHEMA.replace("\"category\":\"image\"", "\"category\":\"IMAGE\"");

    JsonNode model =
        json.readTree(service(catalogue(SD35, FLUX)).forForm(shouting))
            .path("properties")
            .path("model");

    assertThat(model.path("oneOf")).hasSize(2);
    assertThat(model.path("default").asString()).isEqualTo("stable-diffusion-3.5-large");
    assertThatThrownBy(
            () ->
                service(catalogue(SD35, FLUX))
                    .requireClosedSets(shouting, Map.of("model", "not-a-model")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is not a image model");
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
    assertThat(second.path("x-meta").path("unavailableCode").asString())
        .as("a client words the reason in its own language from the code")
        .isEqualTo("gpu_offline");
    assertThat(first.path("x-meta").has("unavailableCode")).isFalse();
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

  @Test
  @DisplayName(
      "A blank model while nothing of the category can run is refused by name, not left to wait for a machine")
  void blankModelWithNothingRunnableIsRefused() {
    FormSchemaService service = service(catalogue(FLUX));

    assertThatThrownBy(() -> service.requireClosedSets(SCHEMA, Map.of("prompt", "a cat")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model: no image model is available right now");
    assertThatThrownBy(() -> service.requireClosedSets(SCHEMA, Map.of("model", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no image model is available right now");
  }

  @Test
  @DisplayName("A schema with no model field never asks the catalogue about a blank one")
  void noModelFieldNoCheck() {
    String schema = "{\"properties\":{\"prompt\":{\"type\":\"string\"}}}";

    assertThatCode(() -> service(catalogue(FLUX)).requireClosedSets(schema, Map.of("prompt", "x")))
        .doesNotThrowAnyException();
  }

  // ── « Automatic » is resolved to the model that will run, so the step is routed by it
  // ───────────────

  @Test
  @DisplayName(
      "A blank model is filled with the runnable default of its category: the run records which model ran and its queue follows")
  void blankModelIsResolvedToTheRunnableDefault() {
    FormSchemaService service = service(catalogue(FLUX, SD35));

    Map<String, Object> resolved =
        service.withResolvedModels(SCHEMA, Map.of("prompt", "a cat", "model", " "));

    assertThat(resolved)
        .containsEntry("model", "stable-diffusion-3.5-large")
        .containsEntry("prompt", "a cat");
    assertThat(service.withResolvedModels(SCHEMA, Map.of("prompt", "a cat")))
        .containsEntry("model", "stable-diffusion-3.5-large");
  }

  @Test
  @DisplayName(
      "A model the person chose is never replaced; no runnable default leaves the inputs as they are; no model field, no change")
  void namedModelAndUnknownsAreLeftAlone() {
    assertThat(
            service(catalogue(FLUX, SD35))
                .withResolvedModels(SCHEMA, Map.of("model", "flux-2-klein-4b")))
        .containsEntry("model", "flux-2-klein-4b");
    assertThat(service(catalogue(FLUX)).withResolvedModels(SCHEMA, Map.of("prompt", "x")))
        .doesNotContainKey("model");
    assertThat(
            service(category -> Optional.empty()).withResolvedModels(SCHEMA, Map.of("prompt", "x")))
        .doesNotContainKey("model");
    String noModel = "{\"properties\":{\"prompt\":{\"type\":\"string\"}}}";
    assertThat(service(catalogue(SD35)).withResolvedModels(noModel, Map.of("prompt", "x")))
        .isEqualTo(Map.of("prompt", "x"));
  }

  // ── A model declares the aspects it can make (x-options.aspects): the server refuses the rest,
  // fills the default ──

  private static final String VIDEO_SCHEMA =
      """
      {"type":"object","properties":{
        "prompt":{"type":"string"},
        "aspect":{"type":"string","enum":["9:16","16:9","1:1"],"default":"9:16"},
        "model":{"type":"string","x-options":{"source":"models","category":"video","aspectField":"aspect",
                 "aspects":{"wan-hosted":["16:9"],"wan-gpu":["9:16","16:9","1:1"]}}}}}
      """;

  private static ModelChoice video(String id, String label, boolean available, boolean isDefault) {
    return new ModelChoice(
        id,
        label,
        "p",
        available ? "cloud" : "local",
        null,
        null,
        null,
        null,
        available,
        null,
        null,
        isDefault);
  }

  private static ModelOptionsClient videoCatalogue(ModelChoice... choices) {
    return category -> "video".equals(category) ? Optional.of(List.of(choices)) : Optional.empty();
  }

  @Test
  @DisplayName(
      "Run 6567e02a: the hosted Wan with the form's default 9:16 is refused before any credit is held, naming what it makes")
  void unsupportedAspectIsRefusedBeforeAnyHold() {
    FormSchemaService service =
        service(
            videoCatalogue(
                video("wan-hosted", "Wan 2.2 hosted", true, true),
                video("wan-gpu", "Wan GPU", false, false)));
    Map<String, Object> inputs =
        service.withResolvedModels(VIDEO_SCHEMA, Map.of("prompt", "x", "aspect", "9:16"));

    assertThatThrownBy(() -> service.requireAspectsFit(VIDEO_SCHEMA, inputs))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("aspect: '9:16' is not supported by Wan 2.2 hosted")
        .hasMessageContaining("16:9");
    assertThatCode(
            () ->
                service.requireAspectsFit(
                    VIDEO_SCHEMA, Map.of("model", "wan-hosted", "aspect", "16:9")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                service.requireAspectsFit(
                    VIDEO_SCHEMA, Map.of("model", "wan-gpu", "aspect", "9:16")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "No aspect given: the model's first supported one fills it (16:9 for the hosted Wan); one given is never replaced")
  void defaultAspectIsTheModelsFirst() {
    FormSchemaService service =
        service(
            videoCatalogue(
                video("wan-hosted", "Wan 2.2 hosted", true, true),
                video("wan-gpu", "Wan GPU", true, false)));

    assertThat(service.withResolvedModels(VIDEO_SCHEMA, Map.of("prompt", "x")))
        .containsEntry("model", "wan-hosted")
        .containsEntry("aspect", "16:9");
    assertThat(service.withResolvedModels(VIDEO_SCHEMA, Map.of("model", "wan-gpu")))
        .containsEntry("aspect", "9:16");
    assertThat(
            service.withResolvedModels(VIDEO_SCHEMA, Map.of("model", "wan-gpu", "aspect", "1:1")))
        .containsEntry("aspect", "1:1");
  }

  @Test
  @DisplayName(
      "A model that declares no aspects, or a schema with no aspect field, is never refused on aspect")
  void modelsWithoutDeclarationAreNotJudged() {
    FormSchemaService service = service(videoCatalogue(video("other", "Other", true, true)));

    assertThatCode(
            () ->
                service.requireAspectsFit(VIDEO_SCHEMA, Map.of("model", "other", "aspect", "1:1")))
        .doesNotThrowAnyException();
    assertThatCode(() -> service.requireAspectsFit(SCHEMA, Map.of("model", "x", "aspect", "9:16")))
        .doesNotThrowAnyException();
  }
}
