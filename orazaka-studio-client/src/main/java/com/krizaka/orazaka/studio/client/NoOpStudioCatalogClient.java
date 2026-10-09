package com.krizaka.orazaka.studio.client;

import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studio.domain.port.StudioCatalogClient;
import java.util.List;
import java.util.Optional;

/**
 * Null Object: an empty catalogue.
 *
 * <p>Empty rather than throwing, unlike its run counterpart: browsing a marketplace that is not
 * deployed is a legitimate question with a legitimate answer, whereas running one is not.
 */
final class NoOpStudioCatalogClient implements StudioCatalogClient {

  @Override
  public List<Studio> browse(String profession) {
    return List.of();
  }

  @Override
  public Optional<Studio> find(String studioKey) {
    return Optional.empty();
  }
}
