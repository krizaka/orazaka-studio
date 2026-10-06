package com.orazaka.studioservice.domain.model;

import java.util.Objects;

/**
 * How one Studio is actually performing (design §14).
 *
 * <p>Success rate is the number that matters and the one nobody sees without this: a Studio can be
 * installed enthusiastically and fail three runs in four, and the difference between those two
 * facts is the difference between a product and a support queue.
 *
 * @param studioKey the Studio measured
 * @param label its localised name
 * @param installations how many actors have it installed
 * @param runs how many runs it has had
 * @param succeeded how many of those finished green
 * @param failed how many failed
 * @param estimatedCredits what one run of its current version holds
 */
public record StudioUsage(
    String studioKey,
    String label,
    long installations,
    long runs,
    long succeeded,
    long failed,
    long estimatedCredits) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public StudioUsage {
    if (studioKey == null || studioKey.isBlank()) {
      throw new IllegalArgumentException("studioKey must not be blank");
    }
    Objects.requireNonNull(label, "label must not be null");
  }

  /**
   * The share of runs that finished green.
   *
   * <p>Computed here rather than in the console, because "success rate" is a claim the product
   * makes and two screens deriving it differently would eventually disagree about whether a Studio
   * works. A Studio nobody has run yet is reported as {@code 0}, not as a perfect score.
   *
   * @return the rate between 0 and 1
   */
  public double successRate() {
    return runs == 0 ? 0d : (double) succeeded / runs;
  }

  /**
   * Whether this Studio is installed but effectively unused — the churn signal of design §14.
   *
   * @return {@code true} when actors installed it and then did not run it
   */
  public boolean installedButUnused() {
    return installations > 0 && runs == 0;
  }
}
