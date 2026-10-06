package com.orazaka.studio.domain.model;

import java.util.regex.Pattern;

/**
 * A capability a pack CONTRIBUTES to {@code orazaka_capabilities}.
 *
 * <p>The two keys are the two independent dispatch discriminants of ADR-038 and must both be
 * stated: {@code routingKey} picks the PROCESS that drains the job, {@code handlerKey} picks the
 * CODE PATH inside it. Neither is derivable from the other, and a capability carrying one without
 * the other is a job that either reaches no worker or reaches one that cannot run it.
 *
 * <p>A DATA pack declares none of these and reuses {@code orazaka.core.*}. The installer still
 * resolves every {@code featureKey} its blueprints name, so declaring nothing is a claim that is
 * checked rather than a gap that is ignored.
 *
 * @param key the capability key as {@code orazaka_capabilities} holds it
 * @param routingKey which process drains it
 * @param handlerKey which executor runs it
 * @param billableUnit the unit metered, {@code null} where the unit belongs to the model
 * @param billableCapability which pricebook row prices it
 * @param inputSchema what a caller may pass, as a JSON Schema (ADR-069); required of a contributed
 *     capability, because an undeclared contract matches nothing and its steps cannot be checked
 * @param outputSchema the fields a successful execution publishes, with types; the other half of
 *     the same contract, and the half nothing declared until ADR-069
 * @param enabled whether it may be dispatched
 */
public record PackCapability(
    String key,
    String routingKey,
    String handlerKey,
    String billableUnit,
    String billableCapability,
    String inputSchema,
    String outputSchema,
    Boolean enabled) {

  private static final Pattern FEATURE_KEY =
      Pattern.compile("^orazaka\\.[a-z0-9]+(\\.[a-z0-9]+)+$");
  private static final Pattern ROUTING_KEY = Pattern.compile("^job\\.[a-z0-9]+\\.[a-z0-9]+$");

  /** Compact canonical constructor enforcing the declaration's invariants (ERR-106). */
  public PackCapability {
    // Schema defaults, honoured here so an omitted optional is not a 400 (ADR-048).
    enabled = enabled == null || enabled;
    // The endpoint rule was delegated here, to the one place it was written correctly. Both are
    // gone with the columns (ADR-069 §5): a contributed capability has no HTTP surface, because a
    // capability is invoked by starting a run.
    if (key == null || !FEATURE_KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("capability key must be a dotted orazaka key: " + key);
    }
    if (routingKey == null || !ROUTING_KEY.matcher(routingKey).matches()) {
      throw new IllegalArgumentException(
          "capability "
              + key
              + " must declare a job.<family>.<action> routingKey, not "
              + routingKey);
    }
    if (handlerKey == null || handlerKey.isBlank()) {
      throw new IllegalArgumentException("capability " + key + " declares no handlerKey");
    }
    // What a capability ACCEPTS and PUBLISHES is required of a pack (ADR-069 §4) — `{}` is the
    // undeclared contract and matches nothing, so a blueprint step over it fails the build rather
    // than dispatching against a promise nobody made.
    inputSchema = inputSchema == null || inputSchema.isBlank() ? "{}" : inputSchema;
    outputSchema = outputSchema == null || outputSchema.isBlank() ? "{}" : outputSchema;
  }
}
