package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studioservice.domain.model.LockReason;
import com.krizaka.orazaka.studioservice.domain.model.StudioAccess;

/**
 * The Studio detail screen: everything needed to decide to install, and nothing needed to run.
 *
 * <p>{@code inputSchema} and {@code configSchema} travel as raw JSON Schema strings so the client
 * generates its own form. A hand-written form per Studio would make every new profession a frontend
 * release, which is the same deploy-per-Studio failure ADR-034 exists to avoid — on the other side
 * of the wire.
 *
 * @param studioKey stable key
 * @param label localised product name
 * @param tagline localised selling line
 * @param description localised long copy, may be {@code null}
 * @param profession the métier this Studio targets
 * @param iconKey resolves in the design system's icon registry
 * @param heroAssetId opaque asset id, may be {@code null}
 * @param pricing FREE, INCLUDED or PAID
 * @param kind VERTICAL or TOOLKIT — a TOOLKIT is included for an entitled actor, never installed
 *     (ADR-061)
 * @param status publication status
 * @param latestVersion the version a fresh install would pin, {@code null} when unpublished
 * @param estimatedCredits what one run of that version holds
 * @param inputSchema JSON Schema for the run form, {@code null} when unpublished
 * @param configSchema JSON Schema for the install dialog, {@code null} when unpublished
 * @param locked whether this actor is blocked from installing
 * @param lockedReason why, {@code NONE} when they are not
 * @param packKey the pack that unlocks it, {@code null} when no purchase applies
 */
public record StudioDetailResponse(
    String studioKey,
    String label,
    String tagline,
    String description,
    String profession,
    String iconKey,
    String heroAssetId,
    String pricing,
    String kind,
    String status,
    String latestVersion,
    long estimatedCredits,
    String inputSchema,
    String configSchema,
    boolean locked,
    LockReason lockedReason,
    String packKey) {

  /**
   * Projects a Studio, its pinned schemas and its access decision onto the wire.
   *
   * @param studio the catalogue row
   * @param description localised long copy, may be {@code null}
   * @param estimatedCredits what one run holds
   * @param inputSchema the run-form schema, may be {@code null}
   * @param configSchema the install-dialog schema, may be {@code null}
   * @param access what this actor may do with it
   * @return the detail payload
   */
  public static StudioDetailResponse from(
      Studio studio,
      String description,
      long estimatedCredits,
      String inputSchema,
      String configSchema,
      StudioAccess access) {
    return new StudioDetailResponse(
        studio.studioKey(),
        studio.label(),
        studio.tagline(),
        description,
        studio.profession(),
        studio.iconKey(),
        studio.heroAssetId(),
        studio.pricing().name(),
        studio.kind().name(),
        studio.status().name(),
        studio.latestVersion(),
        estimatedCredits,
        inputSchema,
        configSchema,
        access.locked(),
        access.reason(),
        access.packKey());
  }
}
