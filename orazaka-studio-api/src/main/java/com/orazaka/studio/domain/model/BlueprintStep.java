package com.orazaka.studio.domain.model;

import com.orazaka.jobs.domain.model.FailureCause;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One node of a blueprint's DAG — the unit of composition, and the reason a new profession needs no
 * Java.
 *
 * <p>A step validates what it owns: its identity, its templates' grammar, its retry and timeout
 * bounds. The rules that need the <i>whole</i> graph — dangling dependencies, cycles, duplicate ids
 * and {@code out} collisions — belong to {@link Blueprint}, which raises {@link
 * com.orazaka.studio.domain.exception.BlueprintValidationException} naming the offending node.
 *
 * @param id unique within the blueprint; kebab-case, because it is addressed from {@code
 *     dependsOn}, from {@code studio_run_step.step_id} and from the authoring UI
 * @param kind what this step does — the interpreter's dispatch discriminant
 * @param featureKey OPAQUE reference into the capability registry; required for {@link
 *     StepKind#CAPABILITY}, meaningless otherwise
 * @param dependsOn ids of the steps that must be terminal before this one is ready; defensively
 *     copied
 * @param inputs templated arguments handed to the executor, e.g. {@code {{steps.brief.text}}};
 *     defensively copied
 * @param out the scope name this step's output is published under for downstream steps
 * @param forEach a fan-out source, e.g. {@code {{inputs.photos}}}; {@code null} for a single shot
 * @param maxParallel concurrency cap for a fan-out — on owned hardware the accelerator is the
 *     scarce resource, so an uncapped {@code forEach} over 40 photos is a self-inflicted outage
 * @param onError what a failure does to the run
 * @param maxAttempts total attempts, at least one
 * @param timeout per-attempt ceiling before the saga fails the step
 * @param condition one of the four supported comparison forms, or {@code null} to always run
 */
public record BlueprintStep(
    String id,
    StepKind kind,
    String featureKey,
    String connectorType,
    Set<String> dependsOn,
    Map<String, String> inputs,
    String out,
    String forEach,
    int maxParallel,
    ErrorPolicy onError,
    Map<FailureCause, ErrorPolicy> onErrorByCause,
    int maxAttempts,
    Duration timeout,
    String condition) {

  /** Addressable from three places, so it may not carry case, whitespace or punctuation. */
  private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{0,59}$");

  /**
   * A step that could run for an hour is not a step, it is an outage — and the sweeper needs an
   * upper bound it can trust to distinguish "slow" from "the worker died".
   */
  private static final Duration MAX_TIMEOUT = Duration.ofHours(1);

  /** Compact canonical constructor enforcing everything a single step can know (ERR-106). */
  public BlueprintStep {
    if (id == null || !ID.matcher(id).matches()) {
      throw new IllegalArgumentException("step id must match " + ID.pattern() + ", was: " + id);
    }
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(onError, "onError must not be null");
    // Additive, not a change of type: `onError` stays the default and every shipped blueprint
    // keeps working unchanged (ADR-048). An author overrides only the categories they care about.
    onErrorByCause = onErrorByCause == null ? Map.of() : Map.copyOf(onErrorByCause);
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1, was: " + maxAttempts);
    }
    if (maxParallel < 0) {
      throw new IllegalArgumentException("maxParallel must be >= 0, was: " + maxParallel);
    }
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "timeout must be within (0, " + MAX_TIMEOUT + "], was: " + timeout);
    }
    dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
    inputs = inputs == null ? Map.of() : Map.copyOf(inputs);

    RunScope.assertValidGrammar(forEach);
    RunScope.assertValidGrammar(condition);
    inputs.values().forEach(RunScope::assertValidGrammar);
  }

  /**
   * The policy for a failure of this cause.
   *
   * <p>What the validation pack had to give up for want of it: *"SKIP when the model was
   * unavailable, FAIL when a guard refused"* is a distinction only the author can make, and until
   * ADR-053 the two arrived identical so the pack took the blunt option. An author who says nothing
   * about a cause gets {@link #onError}, which is what every blueprint written before this existed
   * says about all of them.
   *
   * @param cause the declared cause, {@code null} treated as the default
   * @return the policy to apply
   */
  public ErrorPolicy onErrorFor(FailureCause cause) {
    return cause == null ? onError : onErrorByCause.getOrDefault(cause, onError);
  }
}
