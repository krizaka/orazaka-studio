package com.krizaka.orazaka.studio.client;

import com.krizaka.orazaka.studio.domain.port.StudioCatalogClient;
import com.krizaka.orazaka.studio.domain.port.StudioRunClient;
import com.krizaka.orazaka.studio.domain.port.TurnDeclarationClient;
import com.krizaka.security.token.ServiceTokenProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Selects the Studio adapters at bootstrap.
 *
 * <p>The point of the two-bean arrangement: a consumer depends on {@link StudioRunClient} and never
 * learns whether the marketplace is deployed. This is also what lets {@code orazaka-business} host
 * a Studio use-case without depending on {@code orazaka-studio-service} — which SEAM-002 forbids,
 * and which is why the Intention path goes through this SDK rather than through the service
 * directly.
 *
 * <p>Registered through the AutoConfiguration SPI so a consuming service inherits it by adding the
 * dependency, without widening its component scan.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StudioProperties.class)
public class StudioClientAutoConfiguration {

  /**
   * The real run adapter, wired only when the marketplace is explicitly enabled.
   *
   * @param properties the consumer-side wiring
   * @return an HTTP client pointed at the studio service
   */
  @Bean
  @ConditionalOnProperty(prefix = "orazaka.studio", name = "enabled", havingValue = "true")
  StudioRunClient httpStudioRunClient(StudioProperties properties) {
    return new HttpStudioRunClient(studioRestClient(properties));
  }

  /**
   * The fallback, so the port is always satisfied.
   *
   * @return a client that refuses runs rather than silently dropping them
   */
  @Bean
  @ConditionalOnMissingBean(StudioRunClient.class)
  StudioRunClient noOpStudioRunClient() {
    return new NoOpStudioRunClient();
  }

  /**
   * The real catalogue adapter, wired by the same switch.
   *
   * @param properties the consumer-side wiring
   * @return an HTTP client pointed at the studio service
   */
  @Bean
  @ConditionalOnProperty(prefix = "orazaka.studio", name = "enabled", havingValue = "true")
  StudioCatalogClient httpStudioCatalogClient(StudioProperties properties) {
    return new HttpStudioCatalogClient(studioRestClient(properties));
  }

  /**
   * The fallback, so the port is always satisfied.
   *
   * @return an empty catalogue
   */
  @Bean
  @ConditionalOnMissingBean(StudioCatalogClient.class)
  StudioCatalogClient noOpStudioCatalogClient() {
    return new NoOpStudioCatalogClient();
  }

  /**
   * The turn-declaration adapter, wired by the same switch (ADR-076).
   *
   * @param properties the consumer-side wiring
   * @return an HTTP client pointed at the studio service's internal surface
   */
  @Bean
  @ConditionalOnProperty(prefix = "orazaka.studio", name = "enabled", havingValue = "true")
  TurnDeclarationClient httpTurnDeclarationClient(StudioProperties properties) {
    return new HttpTurnDeclarationClient(studioRestClient(properties));
  }

  /**
   * The fallback, which refuses: a turn under an installation must not run without its guards.
   *
   * @return a client that refuses
   */
  @Bean
  @ConditionalOnMissingBean(TurnDeclarationClient.class)
  TurnDeclarationClient noOpTurnDeclarationClient() {
    return new NoOpTurnDeclarationClient();
  }

  /** One client shape for every adapter. */
  private static RestClient studioRestClient(StudioProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.connectTimeout());
    requestFactory.setReadTimeout(properties.readTimeout());
    ServiceTokenProvider tokens =
        new ServiceTokenProvider(properties.serviceSecret(), "orazaka-studio-client");
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        // Attached here rather than in each adapter: /internal/v1 authentication must not be a
        // thing the next method added to this client can forget.
        .requestInitializer(request -> request.getHeaders().setBearerAuth(tokens.token()))
        .build();
  }
}
