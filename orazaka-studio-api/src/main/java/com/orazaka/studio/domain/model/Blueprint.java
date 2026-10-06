package com.orazaka.studio.domain.model;

import com.orazaka.studio.domain.exception.BlueprintValidationException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable, semver'd version of a Studio's DAG — the executable half of the product.
 *
 * <p>This constructor is the <b>anti-corruption boundary</b> of the Studio context (ERR-127). A
 * blueprint is untrusted input even when an admin authored it, so a graph that cannot be statically
 * validated is never persisted — and that is precisely why the run path downstream needs no
 * defensive checks: by the time the interpreter sees a blueprint, the graph is known acyclic, every
 * dependency resolves, and every template is grammatical.
 *
 * <p>What is <i>not</i> checked here: whether each placeholder resolves against the input schema
 * and the upstream {@code out} names. That needs a JSON Schema reader, which is a dependency this
 * Tier-1 contract may not carry, so it belongs to the service.
 *
 * @param studioKey the Studio this version belongs to
 * @param version semver; immutable once {@link BlueprintStatus#PUBLISHED}
 * @param status where this version sits in its publication lifecycle
 * @param inputSchema JSON Schema (draft 2020-12) that both renders and validates the run form
 * @param steps the DAG — non-empty, acyclic, with resolvable dependencies
 * @param outputs what the actor gets back; defensively copied
 * @param configSchemaDefaults install-time configuration defaults — brand kit, tone, hashtags;
 *     defensively copied
 * @param estimatedCredits the pre-run estimate, and therefore the amount held for a whole run
 * @param changelog what changed against the previous version; the upgrade banner shows it
 * @param publishedAt when this version went live, {@code null} while it is a draft
 */
public record Blueprint(
    String studioKey,
    String version,
    BlueprintStatus status,
    String inputSchema,
    List<BlueprintStep> steps,
    List<BlueprintOutput> outputs,
    Map<String, String> configSchemaDefaults,
    long estimatedCredits,
    String changelog,
    Instant publishedAt) {

  /** Compact canonical constructor: validates the graph as a whole (ERR-106). */
  public Blueprint {
    if (studioKey == null || studioKey.isBlank()) {
      throw new IllegalArgumentException("studioKey must not be blank");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    Objects.requireNonNull(status, "status must not be null");
    if (inputSchema == null || inputSchema.isBlank()) {
      throw new IllegalArgumentException("inputSchema must not be blank");
    }
    if (estimatedCredits < 0) {
      throw new IllegalArgumentException("estimatedCredits must be >= 0, was: " + estimatedCredits);
    }
    if (steps == null || steps.isEmpty()) {
      throw new BlueprintValidationException("a blueprint must declare at least one step");
    }
    steps = List.copyOf(steps);
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
    configSchemaDefaults =
        configSchemaDefaults == null ? Map.of() : Map.copyOf(configSchemaDefaults);

    Set<String> ids = uniqueIds(steps);
    validateNodes(steps, ids);
    assertAcyclic(steps);
  }

  /** Collects the step ids, refusing duplicates: {@code dependsOn} would otherwise be ambiguous. */
  private static Set<String> uniqueIds(List<BlueprintStep> steps) {
    Set<String> ids = new HashSet<>(steps.size());
    for (BlueprintStep step : steps) {
      if (!ids.add(step.id())) {
        throw new BlueprintValidationException(step.id(), "duplicate step id");
      }
    }
    return ids;
  }

  /** Per-node rules that need the sibling set: dependency resolution and {@code out} uniqueness. */
  private static void validateNodes(List<BlueprintStep> steps, Set<String> ids) {
    Set<String> publishedOutputs = new HashSet<>(steps.size());
    for (BlueprintStep step : steps) {
      if (step.kind() == StepKind.CAPABILITY
          && (step.featureKey() == null || step.featureKey().isBlank())) {
        throw new BlueprintValidationException(
            step.id(), "a CAPABILITY step must name a featureKey");
      }
      if (step.kind() == StepKind.CONNECTOR
          && (step.connectorType() == null || step.connectorType().isBlank())) {
        throw new BlueprintValidationException(
            step.id(), "a CONNECTOR step must name the connectorType it dispatches to");
      }
      if (step.forEach() != null && step.maxParallel() < 1) {
        throw new BlueprintValidationException(
            step.id(), "a forEach step must cap its concurrency: maxParallel >= 1");
      }
      for (String dependency : step.dependsOn()) {
        if (!ids.contains(dependency)) {
          throw new BlueprintValidationException(
              step.id(), "dependsOn names an unknown step: " + dependency);
        }
      }
      if (step.out() != null && !publishedOutputs.add(step.out())) {
        throw new BlueprintValidationException(
            step.id(), "out name is already published by another step: " + step.out());
      }
    }
  }

  /**
   * Kahn's algorithm, iteratively. A cycle is not attributable to one node, so it raises a
   * graph-wide violation naming the nodes that never became ready — which is the set an author has
   * to look at.
   *
   * <p>Iterative rather than a recursive DFS on purpose: the depth here is attacker-controlled, and
   * a {@code StackOverflowError} is a denial of service dressed as a validation failure.
   */
  private static void assertAcyclic(List<BlueprintStep> steps) {
    Map<String, Integer> pending = new HashMap<>(steps.size());
    Map<String, List<String>> dependents = new HashMap<>(steps.size());
    for (BlueprintStep step : steps) {
      pending.put(step.id(), step.dependsOn().size());
      for (String dependency : step.dependsOn()) {
        dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(step.id());
      }
    }

    Deque<String> ready = new ArrayDeque<>();
    pending.forEach(
        (id, count) -> {
          if (count == 0) {
            ready.add(id);
          }
        });

    int settled = 0;
    while (!ready.isEmpty()) {
      settled++;
      for (String dependent : dependents.getOrDefault(ready.remove(), List.of())) {
        if (pending.merge(dependent, -1, Integer::sum) == 0) {
          ready.add(dependent);
        }
      }
    }

    if (settled != steps.size()) {
      List<String> cyclic =
          pending.entrySet().stream()
              .filter(e -> e.getValue() > 0)
              .map(Map.Entry::getKey)
              .sorted()
              .toList();
      throw new BlueprintValidationException("the step graph is cyclic, involving: " + cyclic);
    }
  }
}
