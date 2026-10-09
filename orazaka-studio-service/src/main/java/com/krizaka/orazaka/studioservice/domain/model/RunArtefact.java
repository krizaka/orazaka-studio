package com.krizaka.orazaka.studioservice.domain.model;

import java.util.Objects;

/**
 * One artefact a run produced, carrying the presentation the blueprint declared for it.
 *
 * <p>A run stores its outputs as bare {@code key → value}. That is enough to hold the result and
 * not enough to show it: an asset id and a caption are both strings, so without the declared type
 * the client can only print both as text, and a video renders as an opaque identifier next to a
 * copy button.
 *
 * <p>The label and type are joined from the blueprint at read time rather than copied into the run
 * row. Blueprint versions are immutable — the database enforces it with a trigger — so the join is
 * stable: a run read a year later renders exactly as it did the day it finished, without the same
 * fact being stored twice and given two chances to disagree.
 *
 * @param key the output's key, as the blueprint names it
 * @param label the human label to show — the key itself when the blueprint no longer declares it
 * @param type the rendering hint: {@code VIDEO}, {@code IMAGE}, {@code TEXT}
 * @param value what the run actually produced
 */
public record RunArtefact(String key, String label, String type, String value) {

  /** The fallback for an output no blueprint declaration matches. */
  private static final String UNTYPED = "TEXT";

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public RunArtefact {
    Objects.requireNonNull(key, "key must not be null");
    if (key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    // An undeclared output is still worth showing: the run produced it, and hiding a result
    // because its presentation is unknown would lose the actor's work over a cosmetic gap.
    label = label == null || label.isBlank() ? key : label;
    type = type == null || type.isBlank() ? UNTYPED : type;
    value = value == null ? "" : value;
  }
}
