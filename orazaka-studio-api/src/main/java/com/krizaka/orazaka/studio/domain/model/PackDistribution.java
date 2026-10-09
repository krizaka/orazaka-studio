package com.krizaka.orazaka.studio.domain.model;

/**
 * Where a pack may be obtained (ADR-037 §4.1).
 *
 * <p>Recorded from the first manifest so that the open-core split of phase G is a matter of reading
 * a field, not of re-licensing packs that already shipped.
 */
public enum PackDistribution {
  /** In the open-source repository. */
  OSS,
  /** Available only on the managed cloud. */
  CLOUD,
  /** Published by a third party. */
  PARTNER
}
