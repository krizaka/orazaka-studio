package com.krizaka.orazaka.studio.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The marketplace item: an installable business workflow product.
 *
 * <p>Not a payment, not a plan, not a Spring bean. The <i>priced</i> bundle that grants access to
 * one or more Studios is a billing package, referenced here only by an opaque {@link #packKey()} —
 * keeping the commercial half and the executable half separable is why this type is called {@code
 * Studio} and not {@code Package} (ADR-034 §6).
 *
 * @param studioKey stable kebab-case identity, e.g. {@code realestate-reels}; it is the suffix of
 *     the entitlement key and the segment of every URL, so it never changes
 * @param label the product name — the outcome, never the mechanism: "Reels Immobilier", not "visual
 *     generation"
 * @param tagline one selling line for the catalogue card
 * @param profession the métier this Studio targets, and the catalogue's primary filter
 * @param iconKey resolves in the design system's centralised icon registry
 * @param heroAssetId OPAQUE asset id for the catalogue card, {@code null} when none is set
 * @param pricing how an actor obtains the right to install it
 * @param packKey OPAQUE reference into billing; required when {@link StudioPricing#PAID}
 * @param kind the kind of the pack named by {@code packKey} — how this Studio reaches the user.
 *     Read through that one relation, so the pack a refusal tells the user to buy and the pack
 *     whose kind decides installation cannot be two different packs (ADR-061)
 * @param entitlementKey the key the gate reads, by convention {@code studio.<studioKey>} — or a
 *     wildcard tier key, which is how "all Studios included in Ultimate" is expressed without
 *     enumerating them
 * @param status where this item sits in its publication lifecycle
 * @param publisherId {@code orazaka} today, a tenant id once the marketplace opens
 * @param latestVersion semver of the newest published blueprint, {@code null} while none is
 * @param locales the locales this Studio has translations for; defensively copied
 * @param updatedAt when the catalogue row last changed — the client's cache key
 */
public record Studio(
    String studioKey,
    String label,
    String tagline,
    String profession,
    String iconKey,
    String heroAssetId,
    StudioPricing pricing,
    String packKey,
    PackKind kind,
    String entitlementKey,
    StudioStatus status,
    String publisherId,
    String latestVersion,
    Set<String> locales,
    Instant updatedAt) {

  /**
   * The key is a URL segment and an entitlement-key suffix, so it carries no case and no spaces.
   */
  private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,59}$");

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public Studio {
    if (studioKey == null || !KEY.matcher(studioKey).matches()) {
      throw new IllegalArgumentException(
          "studioKey must match " + KEY.pattern() + ", was: " + studioKey);
    }
    requireText(label, "label");
    requireText(profession, "profession");
    requireText(iconKey, "iconKey");
    requireText(entitlementKey, "entitlementKey");
    requireText(publisherId, "publisherId");
    Objects.requireNonNull(pricing, "pricing must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    // Mirrors the studio_pricing_needs_package CHECK: a PAID Studio with nothing to sell is both
    // unsellable and unreachable, and the invariant belongs to the type as much as to the table.
    if (pricing == StudioPricing.PAID && (packKey == null || packKey.isBlank())) {
      throw new IllegalArgumentException("a PAID studio must name the packKey that unlocks it");
    }
    // A Studio's kind is its pack's. One that claims TOOLKIT and names no pack has declared a kind
    // nothing can have given it — the shape of a default that nobody wrote down (AGENTS.md §12).
    if (kind == PackKind.TOOLKIT && (packKey == null || packKey.isBlank())) {
      throw new IllegalArgumentException(
          "studio " + studioKey + " is TOOLKIT but names no packKey to take its kind from");
    }
    locales = locales == null ? Set.of() : Set.copyOf(locales);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
