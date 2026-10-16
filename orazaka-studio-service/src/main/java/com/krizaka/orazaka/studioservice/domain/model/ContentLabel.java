package com.krizaka.orazaka.studioservice.domain.model;

/**
 * What a generation prompt is about, as far as the local classifier can tell.
 *
 * <p>A label is information, not a verdict: what happens to a prompt carrying one is the
 * <b>operator's</b> decision (tenant settings, provider terms). The single exception is {@link
 * #MINORS_RISK}, which no setting can change.
 */
public enum ContentLabel {
  SEXUAL_EXPLICIT,
  SEXUAL_SUGGESTIVE,
  /** Sexual content involving, or apparently involving, a minor. Always refused, everywhere. */
  MINORS_RISK,
  VIOLENCE_GRAPHIC,
  REAL_PERSON_LIKENESS,
  NONE
}
