package com.orazaka.studio.domain.model;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * One crisis line, for one region, <b>with its source and the date it was checked</b>.
 *
 * <p>These are data, and they are the part of a wellbeing pack most likely to be wrong in the way
 * that matters. A model asked to compose a crisis response will produce a number that looks like a
 * crisis line; a pack that ships one it did not verify is worse than a pack that ships none,
 * because the user dials it. So a resource carries {@link #sourceUrl} and {@link #verifiedOn} and
 * cannot be constructed without them.
 *
 * <p>{@link #verifiedOn} exists because these change: a line is renumbered, a service is merged, a
 * charity closes. A date makes staleness visible instead of leaving it to be discovered by someone
 * in crisis.
 *
 * <p><b>A region with no verified resource is a region the pack is not available in.</b> That is
 * the whole policy, and it is enforced at install rather than at runtime: an approximate number is
 * not a smaller version of a correct one.
 *
 * @param region the region this serves, as a BCP-47-ish code the installation carries — {@code FR},
 *     {@code BE-FR}, {@code BE-NL}, {@code CA}
 * @param label what the service calls itself, in the language of its region
 * @param contact how to reach it, exactly as its own source writes it
 * @param availability when it answers, quoted from the source rather than summarised
 * @param sourceUrl where {@link #contact} was read
 * @param verifiedOn when it was last read there
 */
public record CrisisResource(
    String region,
    String label,
    String contact,
    String availability,
    String sourceUrl,
    LocalDate verifiedOn) {

  private static final Pattern REGION = Pattern.compile("^[A-Z]{2}(-[A-Z]{2})?$");

  /** Compact canonical constructor enforcing the resource's invariants (ERR-106). */
  public CrisisResource {
    if (region == null || !REGION.matcher(region).matches()) {
      throw new IllegalArgumentException(
          "crisis resource region must be like FR or BE-FR: " + region);
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("crisis resource for " + region + " has no label");
    }
    if (contact == null || contact.isBlank()) {
      throw new IllegalArgumentException("crisis resource for " + region + " has no contact");
    }
    if (availability == null || availability.isBlank()) {
      throw new IllegalArgumentException(
          "crisis resource for " + region + " states no availability");
    }
    if (sourceUrl == null || !sourceUrl.startsWith("https://")) {
      throw new IllegalArgumentException(
          "crisis resource for "
              + region
              + " must name the https source it was read from; an unsourced crisis number is the"
              + " one thing this pack must never ship");
    }
    if (verifiedOn == null) {
      throw new IllegalArgumentException(
          "crisis resource for " + region + " must carry the date it was verified: these change");
    }
  }

  /**
   * Whether this resource was verified within {@code days} of {@code today}.
   *
   * <p>Not enforced at install — a line does not stop working the day a review is due — but
   * reported, so an operator can see which regions are overdue before a user does.
   *
   * @param today the reference date
   * @param days the review window
   * @return whether it is inside the window
   */
  public boolean isFresh(LocalDate today, int days) {
    return !verifiedOn.isBefore(today.minusDays(days));
  }

  /** The line a refused turn shows, assembled from the source's own words. */
  public String render() {
    return label + " — " + contact + " (" + availability + ")";
  }
}
