package com.orazaka.studio.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One Studio a bundle ships, with the blueprint version it ships it at.
 *
 * <p>Distinct from {@link Studio}, which is the catalogue's read model: this is what a manifest
 * DECLARES, before any row exists. The blueprint travels inline rather than as a path because a
 * bundle is validated and applied as one value — an install that had to reach back to the
 * filesystem could fail after writing half a catalogue.
 *
 * @param key the Studio's stable kebab-case key
 * @param profession the trade it serves, the catalogue's primary filter
 * @param iconKey resolves in the design system's icon registry
 * @param pricing FREE, INCLUDED or PAID; PAID demands the bundle carry a catalog entry
 * @param entitlementKey {@code studio.<key>}, read verbatim by {@code EntitlementSnapshot.allows}
 * @param status where the Studio sits in its publication lifecycle
 * @param publisherId who published it
 * @param sortWeight order in the catalogue, heaviest first
 * @param blueprint the version this bundle ships, already read from its file
 */
public record PackStudio(
    String key,
    String profession,
    String iconKey,
    StudioPricing pricing,
    String entitlementKey,
    StudioStatus status,
    String publisherId,
    Integer sortWeight,
    PackBlueprint blueprint) {

  private static final Pattern KEY = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

  /** Compact canonical constructor enforcing the declaration's invariants (ERR-106). */
  public PackStudio {
    // Schema defaults, honoured here so an omitted optional is not a 400 (ADR-048).
    status = status == null ? StudioStatus.DRAFT : status;
    publisherId = (publisherId == null || publisherId.isBlank()) ? "orazaka" : publisherId;
    sortWeight = sortWeight == null ? 0 : sortWeight;
    if (key == null || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("studio key must be kebab-case: " + key);
    }
    if (profession == null || profession.isBlank()) {
      throw new IllegalArgumentException("studio " + key + " declares no profession");
    }
    if (iconKey == null || iconKey.isBlank()) {
      throw new IllegalArgumentException("studio " + key + " declares no iconKey");
    }
    // The entitlement key is what gates access, so a mismatch would grant one Studio and unlock
    // another. Deriving it would be safer still, but it is stated in the manifest because a pack
    // author must be able to SEE what their pack unlocks.
    if (!("studio." + key).equals(entitlementKey)) {
      throw new IllegalArgumentException(
          "studio "
              + key
              + " must declare entitlementKey 'studio."
              + key
              + "', not "
              + entitlementKey);
    }
    Objects.requireNonNull(pricing, "pricing must not be null");
    Objects.requireNonNull(blueprint, "blueprint must not be null");
    status = status == null ? StudioStatus.DRAFT : status;
    publisherId = publisherId == null || publisherId.isBlank() ? "orazaka" : publisherId;
  }
}
