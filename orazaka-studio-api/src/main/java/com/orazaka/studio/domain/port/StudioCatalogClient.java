package com.orazaka.studio.domain.port;

import com.orazaka.studio.domain.model.Studio;
import java.util.List;
import java.util.Optional;

/**
 * Read side of the Studio contract: what can this platform offer?
 *
 * <p>The catalogue is deliberately <b>not</b> entitlement-filtered. A Studio an actor cannot yet
 * install is still returned, so the UI can render it greyed with its upsell — hiding locked items
 * destroys the funnel, and every refusal is the sharpest upgrade signal the product has (ADR-034
 * §4). Whether an actor may actually install or run one is decided server-side at those two calls,
 * not here.
 */
public interface StudioCatalogClient {

  /**
   * Lists the published Studios of a profession.
   *
   * @param profession the métier to filter on, or {@code null} for the whole catalogue
   * @return the matching Studios, in catalogue order
   */
  List<Studio> browse(String profession);

  /**
   * Resolves one Studio by its stable key.
   *
   * @param studioKey the Studio's key
   * @return the Studio, or empty when no such key exists
   */
  Optional<Studio> find(String studioKey);
}
