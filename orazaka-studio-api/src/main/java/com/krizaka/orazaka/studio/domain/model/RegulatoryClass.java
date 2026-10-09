package com.krizaka.orazaka.studio.domain.model;

import com.krizaka.orazaka.jobs.domain.model.DataClass;

/**
 * How much regulatory weight a Pack carries, and therefore which controls its lifecycle switches
 * on.
 *
 * <p>Introduced with the catalogue and read by <b>nothing yet</b> (ADR-036). The seven controls
 * behind {@link #SENSITIVE} and {@link #REGULATED} — consent gate, non-bypassable safety
 * interceptor, scope guard, sensitive data class, shortened retention, append-only audit trail and
 * the publish gate — are a later phase. The value exists now because a wellbeing pack cannot be
 * special-cased later without rewriting the publish path under time pressure, and because adding
 * the column to a table holding one row is a line where adding it to a table holding three packs of
 * content is a migration.
 *
 * <p>Generalised rather than named after therapy on purpose: a legal-advice pack, a medical-summary
 * pack and a financial-advice pack have the same shape, and the promise the whole Studio
 * architecture makes is that the fourth of them is a <b>row</b>.
 */
public enum RegulatoryClass {

  /** Every pack that exists today. Changes nothing. */
  STANDARD,

  /** Personal but not health data — a legal-drafting pack. Data class, retention and audit. */
  SENSITIVE,

  /** Health-adjacent. Everything {@link #SENSITIVE} carries, plus consent and the safety gates. */
  REGULATED;

  /**
   * The data class a run of a pack in this class carries — stamped once on the run and never
   * re-derived, because the pack can be re-classified and the material cannot (ADR-051).
   *
   * @return the matching {@link DataClass}
   */
  public DataClass dataClass() {
    return switch (this) {
      case STANDARD -> DataClass.STANDARD;
      case SENSITIVE -> DataClass.SENSITIVE;
      case REGULATED -> DataClass.REGULATED;
    };
  }
}
