package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studioservice.domain.model.StudioUsage;

/**
 * One row of the Studio analytics table.
 *
 * <p>{@code successRate} is computed server-side and sent, not left to the console: it is a claim
 * the product makes about whether a Studio works, and two clients deriving it independently would
 * eventually disagree.
 *
 * @param studioKey the Studio measured
 * @param label its name
 * @param installations how many actors have it
 * @param runs how many runs it has had
 * @param succeeded how many finished green
 * @param failed how many failed
 * @param successRate the share that finished green, 0 when never run
 * @param estimatedCredits what one run holds
 * @param installedButUnused the churn signal: installed, never run
 */
public record StudioUsageResponse(
    String studioKey,
    String label,
    long installations,
    long runs,
    long succeeded,
    long failed,
    double successRate,
    long estimatedCredits,
    boolean installedButUnused) {

  /**
   * Projects usage onto the wire.
   *
   * @param usage the measured row
   * @return the response
   */
  public static StudioUsageResponse from(StudioUsage usage) {
    return new StudioUsageResponse(
        usage.studioKey(),
        usage.label(),
        usage.installations(),
        usage.runs(),
        usage.succeeded(),
        usage.failed(),
        usage.successRate(),
        usage.estimatedCredits(),
        usage.installedButUnused());
  }
}
