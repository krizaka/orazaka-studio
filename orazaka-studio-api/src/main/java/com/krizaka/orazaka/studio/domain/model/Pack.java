package com.krizaka.orazaka.studio.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a user buys: a bundle of Studios, with the identity and content that make it browsable.
 *
 * <p>A Pack is <b>not</b> a Studio and not a price. It names {@link #studioKeys()} — plural,
 * always: "le pack prospection qui permet d'avoir des features de prospection". Owning a Pack is
 * also not installing its Studios; an actor may own a Pack of four and install two, which is why
 * the two lifecycles never collapsed into one.
 *
 * <p>What it <b>costs</b> is {@code billing_pack} in the billing database, joined to this row only
 * by the opaque {@link #packKey()} with no foreign key between them (ADR-036, SEAM-001). Billing
 * answers "what does it cost and what does it grant"; this record answers "what is it and what does
 * it do". Keeping the price out of here is deliberate: a stale copied price is a billing dispute.
 *
 * @param packKey stable kebab-case identity, mirrored in {@code billing_pack} and used as a URL
 *     segment, so it never changes
 * @param categoryKey the shelf this Pack is browsed under; resolves to a {@link PackCategory}
 * @param label the localised product name — the outcome, never the mechanism
 * @param tagline one localised selling line for the marketplace card
 * @param description the localised long copy, {@code null} outside the detail screen
 * @param iconKey resolves in the design system's centralised icon registry
 * @param heroAssetId OPAQUE asset id for the card image, {@code null} when none is set
 * @param regulatoryClass how much regulatory weight it carries; read by nothing yet (ADR-036)
 * @param kind how it reaches the user — orthogonal to tier and regulatory class (ADR-061)
 * @param status where it sits in its publication lifecycle
 * @param sortWeight order within its shelf, heaviest first
 * @param studioKeys the Studios this Pack bundles, in shelf order; defensively copied
 * @param locales the locales this Pack has translations for; defensively copied
 * @param updatedAt when the catalogue row last changed — the client's cache key
 */
public record Pack(
    String packKey,
    String categoryKey,
    String label,
    String tagline,
    String description,
    String iconKey,
    String heroAssetId,
    RegulatoryClass regulatoryClass,
    PackKind kind,
    PackStatus status,
    int sortWeight,
    List<String> studioKeys,
    Set<String> locales,
    Instant updatedAt) {

  /** The key is a URL segment and the join value into billing, so it carries no case, no spaces. */
  private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,49}$");

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public Pack {
    if (packKey == null || !KEY.matcher(packKey).matches()) {
      throw new IllegalArgumentException(
          "packKey must match " + KEY.pattern() + ", was: " + packKey);
    }
    if (categoryKey == null || categoryKey.isBlank()) {
      throw new IllegalArgumentException("categoryKey must not be blank");
    }
    requireText(label, "label");
    requireText(iconKey, "iconKey");
    Objects.requireNonNull(regulatoryClass, "regulatoryClass must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    // ck_pack_toolkit_not_regulated, mirrored in the type as studio_pricing_needs_package is in
    // Studio's. The CHECK is the guard; this is where a caller building one in memory finds out.
    // Consent is recorded on an installation row and a TOOLKIT has none, so a REGULATED toolkit's
    // consent gate would have nowhere to live (ADR-061).
    if (kind == PackKind.TOOLKIT && regulatoryClass == RegulatoryClass.REGULATED) {
      throw new IllegalArgumentException(
          "pack "
              + packKey
              + " is a TOOLKIT and REGULATED: consent is recorded on an installation,"
              + " and a TOOLKIT's installation is derived, not stored");
    }
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    studioKeys = studioKeys == null ? List.of() : List.copyOf(studioKeys);
    locales = locales == null ? Set.of() : Set.copyOf(locales);
    // ADR-036 invariant #1. A published Pack that bundles nothing is a shelf item a user can pay
    // for and receive nothing from — a support ticket the buyer opens, not one we catch.
    if (status == PackStatus.PUBLISHED && studioKeys.isEmpty()) {
      throw new IllegalArgumentException(
          "a PUBLISHED pack must bundle at least one studio, " + packKey + " bundles none");
    }
  }

  /**
   * Whether this Pack bundles the given Studio.
   *
   * @param studioKey the Studio to look for
   * @return {@code true} when it is part of the bundle
   */
  public boolean bundles(String studioKey) {
    return studioKeys.contains(studioKey);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
