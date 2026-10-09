package com.krizaka.orazaka.studio.domain.model;

/**
 * Where a Pack sits in its publication lifecycle.
 *
 * <p>Mirrors {@link StudioStatus} deliberately: a Pack and a Studio are both catalogue items an
 * admin authors, and two lifecycles for one authoring gesture would eventually mean two answers to
 * "is this on sale". The difference that matters is {@link #PUBLISHED}, which is the state the
 * coherence invariants bite on — a published Pack must bundle at least one Studio, and every Studio
 * it bundles must itself be published.
 */
public enum PackStatus {

  /** Being authored. Invisible to buyers, and exempt from the coherence invariants. */
  DRAFT,

  /** On the shelf. Every invariant of ADR-036 §4 applies. */
  PUBLISHED,

  /** Pulled from sale. Actors who already bought it keep it — withdrawing is not revoking. */
  WITHDRAWN
}
