package com.orazaka.studio.client;

import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studio.domain.port.StudioCatalogClient;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the catalogue over the studio service's REST surface.
 *
 * <p>Every failure degrades to "nothing on offer" rather than propagating. A catalogue is a shop
 * window: a consumer that cannot reach it should render an empty section, not a stack trace on a
 * page the user came to for something else.
 */
final class HttpStudioCatalogClient implements StudioCatalogClient {

  private static final Logger logger = LoggerFactory.getLogger(HttpStudioCatalogClient.class);

  private static final ParameterizedTypeReference<List<Studio>> STUDIO_LIST =
      new ParameterizedTypeReference<>() {};

  private final RestClient restClient;

  HttpStudioCatalogClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "RestClient cannot be null");
  }

  @Override
  public List<Studio> browse(String profession) {
    try {
      List<Studio> studios =
          restClient
              .get()
              .uri(
                  builder -> {
                    builder.path("/api/v1/studios");
                    if (profession != null && !profession.isBlank()) {
                      builder.queryParam("profession", profession);
                    }
                    return builder.build();
                  })
              .retrieve()
              .body(STUDIO_LIST);
      return studios == null ? List.of() : studios;
    } catch (RestClientException unreachable) {
      logger.warn("Could not read the studio catalogue: {}", unreachable.getMessage());
      return List.of();
    }
  }

  @Override
  public Optional<Studio> find(String studioKey) {
    try {
      return Optional.ofNullable(
          restClient.get().uri("/api/v1/studios/{key}", studioKey).retrieve().body(Studio.class));
    } catch (RestClientException unreachable) {
      logger.warn("Could not read studio {}: {}", studioKey, unreachable.getMessage());
      return Optional.empty();
    }
  }
}
