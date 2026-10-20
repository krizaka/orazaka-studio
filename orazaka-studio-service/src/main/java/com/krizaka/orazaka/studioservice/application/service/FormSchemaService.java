package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studioservice.domain.model.ModelChoice;
import com.krizaka.orazaka.studioservice.domain.port.ModelOptionsClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the form a client renders from a blueprint's input schema, and guards the closed sets.
 *
 * <p>A blueprint says <i>what kind</i> of choice a field is, never the list: {@code "x-options":
 * {"source": "models", "category": "image"}} on a field means "one of the models the catalogue
 * publishes for image". This service turns that into a labelled choice list (standard JSON Schema
 * {@code oneOf} of {@code const}/{@code title}, with the catalogue facts a picker shows in {@code
 * x-meta}) and the default that will run, so every client draws the same picker from the same data
 * and a model added to {@code orazaka_models} appears in the form with no release.
 *
 * <p>The same rules refuse, at run start and before any credit is held, a value that is not one of
 * the choices — a typed model id that does not exist, an aspect that was never offered.
 */
@Service
public class FormSchemaService {

  private static final String OPTIONS = "x-options";
  private static final String SOURCE_MODELS = "models";

  private final ModelOptionsClient modelOptions;
  private final ObjectMapper objectMapper;

  public FormSchemaService(ModelOptionsClient modelOptions, ObjectMapper objectMapper) {
    this.modelOptions = Objects.requireNonNull(modelOptions, "ModelOptionsClient required");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper required");
  }

  /**
   * The schema a client draws its form from: each field fed by the catalogue gets its choices and
   * default. A schema that is null, blank or not JSON is returned as it is.
   *
   * @param inputSchema the blueprint's raw input schema
   * @return the schema with the catalogue's choices written in
   */
  public String forForm(String inputSchema) {
    if (inputSchema == null || inputSchema.isBlank()) {
      return inputSchema;
    }
    JsonNode root = parse(inputSchema);
    if (root == null || !root.path("properties").isObject()) {
      return inputSchema;
    }
    ObjectNode properties = (ObjectNode) root.path("properties");
    boolean changed = false;
    for (Map.Entry<String, JsonNode> field : properties.properties()) {
      String category = modelCategory(field.getValue());
      if (category != null && field.getValue() instanceof ObjectNode spec) {
        Optional<List<ModelChoice>> choices = modelOptions.options(category);
        if (choices.isPresent()) {
          write(spec, choices.get());
          changed = true;
        }
      }
    }
    return changed ? objectMapper.writeValueAsString(root) : inputSchema;
  }

  /**
   * Refuses a value outside its field's closed set.
   *
   * @param inputSchema the blueprint's raw input schema
   * @param inputs what the actor answered
   * @throws IllegalArgumentException naming the field, the value and the choices, or the model that
   *     is not available right now
   */
  public void requireClosedSets(String inputSchema, Map<String, Object> inputs) {
    JsonNode root = parse(inputSchema);
    if (inputs == null || root == null || !root.path("properties").isObject()) {
      return;
    }
    for (Map.Entry<String, JsonNode> field : root.path("properties").properties()) {
      Object supplied = inputs.get(field.getKey());
      if (supplied == null || String.valueOf(supplied).isBlank()) {
        String category = modelCategory(field.getValue());
        if (category != null) {
          requireSomeModelRuns(field.getKey(), category);
        }
        continue;
      }
      String value = String.valueOf(supplied).strip();
      JsonNode spec = field.getValue();
      List<String> fixed = fixedChoices(spec);
      if (!fixed.isEmpty() && !fixed.contains(value)) {
        throw new IllegalArgumentException(
            field.getKey() + ": '" + value + "' is not one of " + String.join(", ", fixed));
      }
      String category = modelCategory(spec);
      if (category != null) {
        requireModel(field.getKey(), value, category);
      }
    }
  }

  /**
   * A model left on « automatic » runs whichever one the platform picks; when none of the category
   * can run (no worker up, no engine), the run is refused here by name instead of waiting for a
   * machine.
   */
  private void requireSomeModelRuns(String field, String category) {
    Optional<List<ModelChoice>> choices = modelOptions.options(category);
    if (choices.isPresent()
        && !choices.get().isEmpty()
        && choices.get().stream().noneMatch(ModelChoice::available)) {
      throw new IllegalArgumentException(
          field
              + ": no "
              + category
              + " model is available right now. Try again in a few minutes.");
    }
  }

  private void requireModel(String field, String value, String category) {
    Optional<List<ModelChoice>> choices = modelOptions.options(category);
    if (choices.isEmpty() || choices.get().isEmpty()) {
      // The catalogue could not be asked (or has none): the executor re-checks against it.
      return;
    }
    ModelChoice chosen =
        choices.get().stream()
            .filter(choice -> choice.value().equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        field
                            + ": '"
                            + value
                            + "' is not a "
                            + category
                            + " model. Choose one of: "
                            + String.join(
                                ", ", choices.get().stream().map(ModelChoice::value).toList())));
    if (!chosen.available()) {
      throw new IllegalArgumentException(
          field
              + ": "
              + (chosen.unavailableReason() != null ? chosen.unavailableReason() : chosen.label())
              + ". Choose another model or leave it on automatic.");
    }
  }

  private static void write(ObjectNode spec, List<ModelChoice> choices) {
    ArrayNode oneOf = spec.putArray("oneOf");
    String defaultValue = null;
    for (ModelChoice choice : choices) {
      ObjectNode entry = oneOf.addObject();
      entry.put("const", choice.value());
      entry.put("title", choice.label());
      if (choice.hint() != null) {
        entry.put("description", choice.hint());
      }
      ObjectNode meta = entry.putObject("x-meta");
      meta.put("provider", choice.provider());
      meta.put("runsOn", choice.runsOn());
      if (choice.speed() != null) {
        meta.put("speed", choice.speed());
      }
      if (choice.typicalSeconds() != null) {
        meta.put("typicalSeconds", choice.typicalSeconds());
      }
      if (choice.priceCents() != null) {
        meta.put("priceCents", choice.priceCents());
      }
      meta.put("available", choice.available());
      if (choice.unavailableReason() != null) {
        meta.put("unavailableReason", choice.unavailableReason());
      }
      if (choice.unavailableCode() != null) {
        meta.put("unavailableCode", choice.unavailableCode());
      }
      if (choice.isDefault()) {
        defaultValue = choice.value();
      }
    }
    spec.remove("enum");
    if (defaultValue != null) {
      spec.put("default", defaultValue);
    }
  }

  /**
   * The closed set a schema states itself: its {@code enum}, or the {@code const}s of {@code
   * oneOf}.
   */
  private static List<String> fixedChoices(JsonNode spec) {
    List<String> values = new ArrayList<>();
    if (spec.path("enum").isArray()) {
      for (JsonNode node : spec.path("enum")) {
        values.add(node.asString());
      }
    } else if (spec.path("oneOf").isArray() && modelCategory(spec) == null) {
      for (JsonNode node : spec.path("oneOf")) {
        if (node.has("const")) {
          values.add(node.path("const").asString());
        }
      }
    }
    return values;
  }

  private static String modelCategory(JsonNode spec) {
    JsonNode options = spec.path(OPTIONS);
    if (SOURCE_MODELS.equals(options.path("source").asString())) {
      String category = options.path("category").asString();
      // The catalogue's categories are lowercase: « CHAT » asked for a category that has no model
      // and drew an
      // empty select on the form (dev, 2026-10-20).
      return category == null || category.isBlank()
          ? null
          : category.strip().toLowerCase(Locale.ROOT);
    }
    return null;
  }

  private JsonNode parse(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (RuntimeException notJson) {
      return null;
    }
  }
}
