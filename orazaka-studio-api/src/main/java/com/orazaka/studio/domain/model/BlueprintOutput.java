package com.orazaka.studio.domain.model;

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
 */
public record BlueprintOutput(String key, String label, String from, String type) {

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
  }
}
