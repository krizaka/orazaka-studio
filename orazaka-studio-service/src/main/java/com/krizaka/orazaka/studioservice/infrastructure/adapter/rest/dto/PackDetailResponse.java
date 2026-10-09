package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studio.domain.model.PackSummary;
import com.krizaka.orazaka.studioservice.domain.model.PackAccess;
import java.util.List;

/**
 * One Pack's detail screen.
 *
 * <p>The card's payload plus the long copy and the shelf it sits on, resolved once here rather than
 * by a second client call — the detail screen renders its heading from the same shelf the
 * marketplace grouped the card under, and two round trips could disagree about which one that is.
 *
 * @param packKey stable key
 * @param category the shelf, already localised
 * @param label localised product name
 * @param tagline localised selling line, may be {@code null}
 * @param description the localised long copy, {@code null} when the locale carries none
 * @param iconKey resolves in the design system's icon registry
 * @param heroAssetId opaque asset id for the hero image, may be {@code null}
 * @param regulatoryClass how much regulatory weight the Pack carries; nothing acts on it yet
 * @param kind VERTICAL or TOOLKIT — a TOOLKIT is included for an entitled actor, never installed
 *     (ADR-061)
 * @param access how THIS actor gets this pack: INCLUDED, BUYABLE or UNAVAILABLE (ADR-066)
 * @param status where the Pack sits in its publication lifecycle
 * @param studioKeys the Studios this Pack unlocks, in bundle order
 * @param locales the locales this Pack is translated into
 * @param priceCents list price, or {@code null} when billing is unreachable
 * @param includedCredits credits granted at purchase, or {@code null} when billing is unreachable
 */
public record PackDetailResponse(
    String packKey,
    PackCategoryResponse category,
    String label,
    String tagline,
    String description,
    String iconKey,
    String heroAssetId,
    String regulatoryClass,
    String kind,
    String access,
    String status,
    List<String> studioKeys,
    List<String> locales,
    Integer priceCents,
    Long includedCredits) {

  /**
   * Projects a priced catalogue row and its long copy onto the wire.
   *
   * @param summary the Pack, its shelf and its price
   * @param access the band this actor sees it in
   * @return the detail payload
   */
  public static PackDetailResponse from(PackSummary summary, PackAccess access) {
    return new PackDetailResponse(
        summary.pack().packKey(),
        PackCategoryResponse.from(summary.category()),
        summary.pack().label(),
        summary.pack().tagline(),
        summary.pack().description(),
        summary.pack().iconKey(),
        summary.pack().heroAssetId(),
        summary.pack().regulatoryClass().name(),
        summary.pack().kind().name(),
        access.name(),
        summary.pack().status().name(),
        summary.pack().studioKeys(),
        summary.pack().locales().stream().sorted().toList(),
        summary.priceCents(),
        summary.includedCredits());
  }
}
