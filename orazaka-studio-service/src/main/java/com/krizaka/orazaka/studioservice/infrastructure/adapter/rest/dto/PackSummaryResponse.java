package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.studio.domain.model.PackSummary;
import com.krizaka.orazaka.studioservice.domain.model.PackAccess;
import java.util.List;

/**
 * One marketplace card.
 *
 * <p>{@code priceCents} is nullable and that is load-bearing: {@code 0} means free, {@code null}
 * means billing did not answer. The client renders the second as "—". Collapsing them would have
 * the marketplace advertise a price the product never agreed to, every time the billing service
 * restarts (ADR-036 §4.4).
 *
 * <p>{@code studioKeys} travels with the card because a Pack is a <b>bundle</b>: "what do I get" is
 * the question the card exists to answer, and resolving it client-side would be one request per
 * card.
 *
 * @param packKey stable key, and the URL segment of the detail route
 * @param categoryKey the shelf this card is grouped under
 * @param label localised product name
 * @param tagline localised selling line, may be {@code null}
 * @param iconKey resolves in the design system's icon registry
 * @param heroAssetId opaque asset id for the card image, may be {@code null}
 * @param regulatoryClass how much regulatory weight the Pack carries; nothing acts on it yet
 * @param kind VERTICAL or TOOLKIT — a TOOLKIT is included for an entitled actor, never installed
 *     (ADR-061)
 * @param access how THIS actor gets this pack: INCLUDED, BUYABLE or UNAVAILABLE. Decided on the
 *     server from the kind and the entitlement snapshot, because the client reading {@code kind}
 *     alone told an unentitled actor a pack was included (ADR-066)
 * @param studioKeys the Studios this Pack unlocks, in bundle order
 * @param priceCents list price, or {@code null} when billing is unreachable
 * @param includedCredits credits granted at purchase, or {@code null} when billing is unreachable
 */
public record PackSummaryResponse(
    String packKey,
    String categoryKey,
    String label,
    String tagline,
    String iconKey,
    String heroAssetId,
    String regulatoryClass,
    String kind,
    String access,
    List<String> studioKeys,
    Integer priceCents,
    Long includedCredits) {

  /**
   * Projects a priced catalogue row onto the wire.
   *
   * @param summary the card, with its shelf and its price
   * @param access the band this actor sees it in
   * @return the wire shape
   */
  public static PackSummaryResponse from(PackSummary summary, PackAccess access) {
    return new PackSummaryResponse(
        summary.pack().packKey(),
        summary.category().categoryKey(),
        summary.pack().label(),
        summary.pack().tagline(),
        summary.pack().iconKey(),
        summary.pack().heroAssetId(),
        summary.pack().regulatoryClass().name(),
        summary.pack().kind().name(),
        access.name(),
        summary.pack().studioKeys(),
        summary.priceCents(),
        summary.includedCredits());
  }
}
