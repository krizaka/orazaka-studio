package com.orazaka.studio.domain.model;

/**
 * What a catalogued pack costs and how many credits come with it — the {@code billing_pack} half.
 *
 * <p>It travels in the manifest but is applied in the billing context, across a service boundary
 * and a database boundary, which is why the installer applies it before the catalogue rows that
 * depend on it: an entitlement that does not exist yet locks out exactly the customer who just paid
 * (ADR-036, invariant #3).
 *
 * @param priceCents list price
 * @param includedCredits credits bundled with the purchase
 * @param isActive whether it can be sold
 */
public record PackPricing(int priceCents, long includedCredits, Boolean isActive) {

  /**
   * Compact canonical constructor enforcing the pricing's invariants (ERR-106).
   *
   * <p><b>Optional on the wire means optional here.</b> {@code pack.schema.json} declares this
   * field optional with a default, so an external author who omits it is following the published
   * contract. A primitive component cannot express that — Jackson answers a missing field with
   * "Cannot map null into type boolean" and a 400 with no message, which is what phase G's
   * acceptance criterion hit first. The default lives in this compact constructor, once, so the
   * schema and the type cannot drift (ADR-048).
   */
  public PackPricing {
    isActive = isActive == null || isActive;
    if (priceCents < 0 || includedCredits < 0) {
      throw new IllegalArgumentException("price and included credits must be >= 0");
    }
  }
}
