package com.krizaka.orazaka.studio.domain.model;

/**
 * How a Pack reaches the user: chosen and installed, or simply had.
 *
 * <p>One of <b>three orthogonal classifications</b> a pack carries, and none is derived from
 * another (ADR-061). {@link PackTier} says what the pack contributes to the platform, {@link
 * RegulatoryClass} says what controls it forces, and this says how it reaches the user. A {@code
 * TOOLKIT} may be Tier-D, Tier-C or Tier-W, and {@code STANDARD} or {@code SENSITIVE}. The one
 * combination forbidden — {@code TOOLKIT} with {@code REGULATED} — is forbidden because of where
 * consent is recorded, not because the kind implies a class, and it is enforced by the database.
 *
 * <p>Nor is it read from the pack key's spelling. {@code echo-toolkit} is a {@code VERTICAL}: it is
 * the Tier-W reference pack, named for what it demonstrates, and a user installs it like any other.
 */
public enum PackKind {

  /**
   * A pack a user chooses and installs. Its installation is a row: it pins a blueprint version,
   * holds the actor's configuration, and records consent where the pack requires it.
   */
  VERTICAL,

  /**
   * Platform capability an entitled actor simply has. Its installation is <b>derived</b> from the
   * entitlement and never stored, so "entitled but not installed" is not a state it can be in. With
   * no row there is no pin: a run resolves the latest published version once, at start, and records
   * it on the run.
   */
  TOOLKIT
}
