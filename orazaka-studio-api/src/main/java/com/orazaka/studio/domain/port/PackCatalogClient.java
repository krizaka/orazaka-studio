package com.orazaka.studio.domain.port;

import com.orazaka.studio.domain.model.Pack;
import com.orazaka.studio.domain.model.PackCategory;
import com.orazaka.studio.domain.model.PackSummary;
import java.util.List;
import java.util.Optional;

/**
 * Read side of the Pack contract: what is on the marketplace, and on which shelf?
 *
 * <p>Sibling of {@link StudioCatalogClient} and deliberately the same shape — a Pack and a Studio
 * are both browsed before they are owned, and both are returned whatever the caller's entitlements.
 * Hiding what an actor cannot yet buy destroys the funnel.
 *
 * <p>Localised at the port rather than downstream: a catalogue is presentation data, and a caller
 * that had to translate it afterwards would need the i18n tables this contract exists to hide.
 *
 * <p>Prices arrive from billing across a context boundary and may be absent — see {@link
 * PackSummary}. A catalogue that fails because the billing service is restarting is a
 * self-inflicted outage on a marketing page, so implementations degrade rather than throw.
 */
public interface PackCatalogClient {

  /**
   * Lists the published Packs of a shelf.
   *
   * @param categoryKey the shelf to browse, or {@code null} for every shelf
   * @param locale the caller's locale; rows without a translation fall back to the base row
   * @return the matching Packs with their shelf and price, in catalogue order
   */
  List<PackSummary> browse(String categoryKey, String locale);

  /**
   * Resolves one Pack by its stable key, whatever its publication status.
   *
   * @param packKey the Pack's key
   * @param locale the caller's locale
   * @return the Pack, or empty when no such key exists
   */
  Optional<Pack> find(String packKey, String locale);

  /**
   * The shelves themselves, for the marketplace's own headings.
   *
   * @param locale the caller's locale
   * @return the active categories, heaviest sort weight first
   */
  List<PackCategory> categories(String locale);
}
