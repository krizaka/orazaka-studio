package com.orazaka.studioservice.domain.model;

import java.util.Objects;

/**
 * One button of the chat composer: a Studio the actor can run from a single thing they already
 * hold.
 *
 * <p>The chat bar keeps its buttons (ADR-068 §2) and each one now launches a run. What a button
 * needs is far less than a catalogue card: the Studio to run, what to call it, what to draw, and
 * <b>which single input to put the composer's content into</b>.
 *
 * @param studioKey the Studio a click launches a run of
 * @param label the Studio's label in the caller's locale, from the pack's own i18n
 * @param iconKey the Studio's icon, from the same place
 * @param version the published blueprint version the button's row was derived from
 * @param capabilityKey the capability its single step dispatches to — what the client asks the
 *     model catalogue about, and the only thing it still needs the job plane's vocabulary for
 * @param inputKey the one input the composer fills
 * @param inputKind what that input holds, so the composer knows what to put in it
 * @param promptKey where the composer's prose goes — the required input itself when it is text, the
 *     schema's one optional defaulted string when the required input is an attachment, and {@code
 *     null} when the schema offers nowhere to put it
 * @param locked whether the actor may not run it — rendered, greyed, never hidden
 * @param lockedReason why, when locked
 */
public record ComposerStudio(
    String studioKey,
    String label,
    String iconKey,
    String version,
    String capabilityKey,
    String inputKey,
    ComposerStudio.InputKind inputKind,
    String promptKey,
    boolean locked,
    LockReason lockedReason) {

  /**
   * What the composer puts in the single required input.
   *
   * <p>The membership question is structural — one step, one required input — but this one is not,
   * and could not be: an opaque asset id and a sentence are both {@code "type": "string"} and no
   * amount of shape separates them. This is the case AGENTS.md §12 reserves for a declaration, and
   * JSON Schema already has the keyword for it: the pack writes {@code "format": "asset-id"} on the
   * input that holds one. A schema that declares nothing is read as text, which is what a composer
   * holds by default.
   */
  public enum InputKind {
    /** What the user typed. */
    TEXT,
    /** An asset the user attached, referenced by id (ADR-065). */
    ASSET
  }

  /** Compact canonical constructor enforcing the button's invariants (ERR-106). */
  public ComposerStudio {
    Objects.requireNonNull(studioKey, "studioKey must not be null");
    Objects.requireNonNull(label, "label must not be null");
    Objects.requireNonNull(version, "version must not be null");
    Objects.requireNonNull(capabilityKey, "capabilityKey must not be null");
    Objects.requireNonNull(inputKey, "inputKey must not be null");
    Objects.requireNonNull(inputKind, "inputKind must not be null");
    Objects.requireNonNull(lockedReason, "lockedReason must not be null");
    if (locked == (lockedReason == LockReason.NONE)) {
      throw new IllegalArgumentException(
          "locked and reason disagree: " + locked + " / " + lockedReason);
    }
  }
}
