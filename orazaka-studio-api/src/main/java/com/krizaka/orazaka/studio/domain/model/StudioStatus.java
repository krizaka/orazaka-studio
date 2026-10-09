package com.krizaka.orazaka.studio.domain.model;

/**
 * Publication lifecycle of a marketplace item.
 *
 * <p>The review states exist from day one even though the marketplace is single-publisher today:
 * {@code DRAFT → IN_REVIEW → PUBLISHED} is the workflow third-party publishers will need, and
 * retrofitting a lifecycle onto rows that never had one is strictly harder than declaring it now
 * (ADR-034 §2).
 *
 * <p>There is no deleted state. A Studio whose runs are still referenced is withdrawn, never
 * dropped.
 */
public enum StudioStatus {

  /** Authored, not yet submitted. Invisible in the catalogue. */
  DRAFT,

  /** Submitted for review by a publisher. Invisible in the catalogue. */
  IN_REVIEW,

  /** Live in the catalogue and installable. */
  PUBLISHED,

  /** Still installed and runnable by existing actors, but no longer offered to new ones. */
  DEPRECATED,

  /** Pulled: not offered, not installable. Existing installations are paused, never deleted. */
  WITHDRAWN
}
