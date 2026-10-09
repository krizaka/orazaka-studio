package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studioservice.domain.model.LockReason;
import com.krizaka.orazaka.studioservice.domain.model.StudioAccess;

/**
 * One catalogue card.
 *
 * <p>{@code locked} travels with the card rather than filtering it out: a Studio the actor cannot
 * yet install is the top of the funnel, so the UI greys it and shows {@code packKey} as a buy
 * button (ADR-034 §4).
 *
 * @param studioKey stable key, and the URL segment of the detail route
 * @param label localised product name
 * @param tagline localised selling line
 * @param profession the métier this Studio targets
 * @param iconKey resolves in the design system's icon registry
 * @param heroAssetId opaque asset id for the card image, may be {@code null}
 * @param pricing FREE, INCLUDED or PAID
 * @param kind VERTICAL or TOOLKIT — a TOOLKIT is included for an entitled actor, never installed
 *     (ADR-061)
 * @param latestVersion the version a fresh install would pin, {@code null} when unpublished
 * @param locked whether this actor is blocked from installing
 * @param lockedReason why, {@code NONE} when they are not
 * @param packKey the pack that unlocks it, {@code null} when no purchase applies
 */
public record StudioSummaryResponse(
    String studioKey,
    String label,
    String tagline,
    String profession,
    String iconKey,
    String heroAssetId,
    String pricing,
    String kind,
    String latestVersion,
    boolean locked,
    LockReason lockedReason,
    String packKey) {

  /**
   * Projects a Studio and its access decision onto the wire.
   *
   * @param studio the catalogue row
   * @param access what this actor may do with it
   * @return the card
   */
  public static StudioSummaryResponse from(Studio studio, StudioAccess access) {
    return new StudioSummaryResponse(
        studio.studioKey(),
        studio.label(),
        studio.tagline(),
        studio.profession(),
        studio.iconKey(),
        studio.heroAssetId(),
        studio.pricing().name(),
        studio.kind().name(),
        studio.latestVersion(),
        access.locked(),
        access.reason(),
        access.packKey());
  }
}
