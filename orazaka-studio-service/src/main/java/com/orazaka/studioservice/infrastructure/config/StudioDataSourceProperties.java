package com.orazaka.studioservice.infrastructure.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The studio service's own datasource wiring ({@code orazaka.studio-service.datasource}), bound
 * from {@code STUDIO_DB_*} only. A dedicated prefix (instead of {@code spring.datasource}) keeps
 * the shared local {@code .env} — which exports {@code SPRING_DATASOURCE_*} for the app database —
 * from hijacking this service's connection through Spring's env-var precedence over yaml.
 *
 * @param url the JDBC URL of {@code orazaka_studio_db}
 * @param username the service's own database role
 * @param password the role's password
 */
@ConfigurationProperties(prefix = "orazaka.studio-service.datasource")
public record StudioDataSourceProperties(String url, String username, String password) {

  /** Compact canonical constructor rejecting an unusable connection at bootstrap (ERR-106). */
  public StudioDataSourceProperties {
    Objects.requireNonNull(url, "studio datasource url is required");
    Objects.requireNonNull(username, "studio datasource username is required");
    Objects.requireNonNull(password, "studio datasource password is required");
    if (!url.startsWith("jdbc:postgresql:")) {
      throw new IllegalArgumentException("studio datasource url must be a postgresql JDBC url");
    }
  }
}
