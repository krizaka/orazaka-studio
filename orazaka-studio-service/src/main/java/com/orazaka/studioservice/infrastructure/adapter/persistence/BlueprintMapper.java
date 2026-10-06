package com.orazaka.studioservice.infrastructure.adapter.persistence;

import com.orazaka.jobs.domain.model.FailureCause;
import com.orazaka.studio.domain.exception.BlueprintValidationException;
import com.orazaka.studio.domain.model.Blueprint;
import com.orazaka.studio.domain.model.BlueprintOutput;
import com.orazaka.studio.domain.model.BlueprintStatus;
import com.orazaka.studio.domain.model.BlueprintStep;
import com.orazaka.studio.domain.model.ErrorPolicy;
import com.orazaka.studio.domain.model.StepKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Turns the stored blueprint JSON into validated records — the anti-corruption boundary of the run
 * path (ERR-127).
 *
 * <p>Everything the DSL leaves optional is defaulted here, once, so no downstream code has to ask
 * "what if this step declares no retry policy". Anything the records reject throws {@link
 * BlueprintValidationException}, which means a malformed blueprint fails at load rather than
 * halfway through a run that has already spent credits.
 *
 * <p>Package-private, final, static: a mapper, not a service (ERR-107/ERR-129).
 */
final class BlueprintMapper {

  /** A step that declares no retry policy fails the run — the safe reading of silence. */
  private static final ErrorPolicy DEFAULT_ERROR_POLICY = ErrorPolicy.FAIL;

  private static final int DEFAULT_MAX_ATTEMPTS = 1;

  /** Long enough for an MLX video job, short enough that the sweeper can still call a stall. */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

  private BlueprintMapper() {}

  /**
   * Parses one blueprint version.
   *
   * @param studioKey the Studio
   * @param version the semver
   * @param status the publication status
   * @param definition the stored {@code definition} JSON — steps and outputs
   * @param inputSchema the stored run-form JSON Schema
   * @param configSchemaDefaults install-time defaults, flattened
   * @param estimatedCredits what one run holds
   * @param changelog what changed
   * @param publishedAt when it went live
   * @return the validated blueprint
   * @throws BlueprintValidationException when the graph cannot be statically validated
   */
  static Blueprint toBlueprint(
      String studioKey,
      String version,
      BlueprintStatus status,
      JsonNode definition,
      String inputSchema,
      Map<String, String> configSchemaDefaults,
      long estimatedCredits,
      String changelog,
      Instant publishedAt) {
    return new Blueprint(
        studioKey,
        version,
        status,
        inputSchema,
        readSteps(definition.path("steps")),
        readOutputs(definition.path("outputs")),
        configSchemaDefaults,
        estimatedCredits,
        changelog,
        publishedAt);
  }

  private static List<BlueprintStep> readSteps(JsonNode steps) {
    if (!steps.isArray()) {
      throw new BlueprintValidationException("definition.steps must be an array");
    }
    List<BlueprintStep> parsed = new ArrayList<>();
    for (JsonNode step : steps) {
      parsed.add(readStep(step));
    }
    return parsed;
  }

  private static BlueprintStep readStep(JsonNode step) {
    String id = text(step, "id");
    try {
      return new BlueprintStep(
          id,
          StepKind.valueOf(textOr(step, "kind", StepKind.CAPABILITY.name())),
          text(step, "featureKey"),
          text(step, "connectorType"),
          readStringSet(step.path("dependsOn")),
          readStringMap(step.path("inputs")),
          text(step, "out"),
          text(step, "forEach"),
          step.path("maxParallel").asInt(0),
          ErrorPolicy.valueOf(textOr(step, "onError", DEFAULT_ERROR_POLICY.name())),
          readErrorPolicyByCause(step.path("onErrorByCause")),
          step.path("maxAttempts").asInt(DEFAULT_MAX_ATTEMPTS),
          readTimeout(step),
          text(step, "condition"));
    } catch (IllegalArgumentException | NullPointerException invalid) {
      // Re-raised as a blueprint violation naming the node: an admin needs the step, not a stack
      // trace pointing at a record constructor.
      throw new BlueprintValidationException(id, invalid.getMessage());
    }
  }

  /**
   * Per-cause overrides of {@code onError} (ADR-053).
   *
   * <p>An unknown cause name is a blueprint the author got wrong, and it is refused rather than
   * ignored: a policy silently dropped because {@code GAURD_REFUSAL} was misspelled would look
   * exactly like the policy being applied and not helping.
   */
  private static Map<FailureCause, ErrorPolicy> readErrorPolicyByCause(JsonNode node) {
    if (node == null || !node.isObject() || node.isEmpty()) {
      return Map.of();
    }
    Map<FailureCause, ErrorPolicy> policies = new EnumMap<>(FailureCause.class);
    node.propertyStream()
        .forEach(
            property -> {
              FailureCause cause = FailureCause.of(property.getKey());
              if (!cause.name().equalsIgnoreCase(property.getKey().trim())) {
                throw new IllegalArgumentException(
                    "onErrorByCause names no such cause: " + property.getKey());
              }
              policies.put(cause, ErrorPolicy.valueOf(property.getValue().asString()));
            });
    return policies;
  }

  private static Duration readTimeout(JsonNode step) {
    String raw = text(step, "timeout");
    return raw == null ? DEFAULT_TIMEOUT : Duration.parse(raw);
  }

  private static List<BlueprintOutput> readOutputs(JsonNode outputs) {
    if (!outputs.isArray()) {
      return List.of();
    }
    List<BlueprintOutput> parsed = new ArrayList<>();
    for (JsonNode output : outputs) {
      parsed.add(
          new BlueprintOutput(
              text(output, "key"),
              text(output, "label"),
              text(output, "from"),
              textOr(output, "type", "TEXT")));
    }
    return parsed;
  }

  private static Set<String> readStringSet(JsonNode node) {
    if (!node.isArray()) {
      return Set.of();
    }
    Set<String> values = new LinkedHashSet<>();
    node.forEach(element -> values.add(element.asString()));
    return values;
  }

  private static Map<String, String> readStringMap(JsonNode node) {
    if (!node.isObject()) {
      return Map.of();
    }
    Map<String, String> values = new LinkedHashMap<>();
    node.propertyStream()
        .forEach(
            property ->
                values.put(
                    property.getKey(),
                    property.getValue().isTextual()
                        ? property.getValue().asString()
                        : property.getValue().toString()));
    return values;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asString();
  }

  private static String textOr(JsonNode node, String field, String fallback) {
    String value = text(node, field);
    return value == null || value.isBlank() ? fallback : value;
  }
}
