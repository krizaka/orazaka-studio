package com.krizaka.orazaka.studio.domain.model;

/**
 * Publication lifecycle of one blueprint version.
 *
 * <p>The transition into {@link #PUBLISHED} is one-way for the <i>content</i>: the {@code
 * trg_studio_blueprint_immutable} trigger refuses any later change to the definition or the input
 * schema, and refuses every delete. Editing a published version means minting a new one, because an
 * installation pins a version and a changed prompt is a changed product (ADR-034 §5).
 */
public enum BlueprintStatus {

  /** Authored and still mutable. Cannot be pinned by an installation. */
  DRAFT,

  /** Immutable and pinnable. The only status a run may execute. */
  PUBLISHED,

  /**
   * Superseded. Still executable by the installations that pinned it — the terminal state, because
   * deleting a version that runs reference would destroy their reproducibility.
   */
  DEPRECATED
}
