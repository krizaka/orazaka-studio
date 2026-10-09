package com.krizaka.orazaka.studioservice.domain.port;

import com.krizaka.orazaka.studio.domain.model.Pack;
import com.krizaka.orazaka.studio.domain.model.PackCategory;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port: read the Pack catalogue, already localised and already bundled.
 *
 * <p>Returns the Tier-1 {@link Pack} record, never rows — the implementation resolves the i18n
 * overlay and the {@code pack_studio} bundle at the boundary, so invariant #1 ("a PUBLISHED pack
 * bundles at least one Studio") is enforced by the record's constructor on every read rather than
 * by a check some caller might skip (ERR-127).
 *
 * <p>Prices are deliberately absent from this port. They live in the billing context and are read
 * across it through {@code PackPricingClient}; copying them into the studio database would make a
 * stale price representable, and a stale price is a billing dispute.
 */
public interface PackRepository {

  /**
   * The published catalogue, optionally narrowed to one shelf.
   *
   * @param categoryKey the shelf to filter on, or {@code null} for everything
   * @param locale the caller's locale; a Pack without that translation falls back
   * @return the Packs, heaviest {@code sort_weight} first
   */
  List<Pack> browse(String categoryKey, String locale);

  /**
   * One Pack by key, whatever its publication status.
   *
   * <p>Drafts resolve on purpose: the admin console reads them through the same path, and gating
   * that is a {@code @PreAuthorize} concern on the controller, not a query concern here.
   *
   * @param packKey the Pack's key
   * @param locale the caller's locale
   * @return the Pack, or empty when no such key exists
   */
  Optional<Pack> find(String packKey, String locale);

  /**
   * The active shelves, for the marketplace's headings.
   *
   * @param locale the caller's locale
   * @return the categories, heaviest {@code sort_weight} first
   */
  List<PackCategory> categories(String locale);
}
