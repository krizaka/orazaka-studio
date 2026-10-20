package com.krizaka.orazaka.studio.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * One artefact a run hands back to the actor.
 *
 * <p>Outputs are declared rather than inferred from the last step, because what a professional came
 * for — the Reel, its caption, its hashtags — is rarely one step's output, and naming them is what
 * lets the run-detail screen render each with the right control (a player, a copy button).
 *
 * @param key stable identifier of this output within the blueprint
 * @param label what the actor sees; localised through the Studio's i18n rows
 * @param from a single placeholder pointing at the value, e.g. {@code {{steps.reel.assetId}}}
 * @param type the rendering hint — {@code VIDEO}, {@code IMAGE}, {@code TEXT}; a free string on
 *     purpose, since a closed enum here would make a new output medium a deploy, which is the one
 *     thing ADR-034 exists to avoid
 * @param schema the JSON Schema a {@code JSON} output must satisfy, as JSON text, or {@code null}.
 *     Declared by the author and enforced by the saga before the run succeeds (ADR-076): a model
 *     asked for an object returns prose often enough that "type: JSON" without a contract is a
 *     rendering hint the value does not keep
 */
public record BlueprintOutput(
    String key, String label, String from, String type, String schema, Map<String, String> labels) {

  /**
   * Compact canonical constructor; validates its own template grammar as the field owner (ERR-106).
   */
  public BlueprintOutput {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("output key must not be blank");
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("output label must not be blank");
    }
    if (from == null || from.isBlank()) {
      throw new IllegalArgumentException("output '" + key + "' must declare where it comes from");
    }
    Objects.requireNonNull(type, "output type must not be null");
    RunScope.assertValidGrammar(from);
    schema = schema == null || schema.isBlank() ? null : schema;
    labels = labels == null ? Map.of() : Map.copyOf(labels);
    if (schema != null && !"JSON".equals(type)) {
      // A contract on a value nobody parses would be a declaration that is never checked.
      throw new IllegalArgumentException(
          "output '" + key + "' declares a schema but is of type " + type + ", not JSON");
    }
  }

  /**
   * An output with no contract — every output a blueprint wrote before contracts existed.
   *
   * @param key stable identifier
   * @param label what the actor sees
   * @param from where the value comes from
   * @param type the rendering hint
   */
  public BlueprintOutput(String key, String label, String from, String type) {
    this(key, label, from, type, null, Map.of());
  }

  /**
   * An output with a contract and no translations.
   *
   * @param key stable identifier
   * @param label what the actor sees
   * @param from where the value comes from
   * @param type the rendering hint
   * @param schema the contract of a JSON output, or null
   */
  public BlueprintOutput(String key, String label, String from, String type, String schema) {
    this(key, label, from, type, schema, Map.of());
  }
}
