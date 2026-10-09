package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import com.krizaka.orazaka.studioservice.domain.model.BlueprintVersion;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class StudioCatalogServiceTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final StudioCatalogService catalogService =
      new StudioCatalogService(jdbcTemplate, new ColumnValueResolver(new ObjectMapper()));

  private static Studio studio(String key) {
    return new Studio(
        key,
        "Vitrine Artisan",
        null,
        "trades",
        "studio",
        null,
        StudioPricing.FREE,
        null,
        PackKind.VERTICAL,
        "studio." + key,
        com.krizaka.orazaka.studio.domain.model.StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        java.util.Set.of("fr"),
        Instant.parse("2026-08-05T10:00:00Z"));
  }

  @Test
  @DisplayName("browse filters on the published status and passes the locale to the i18n overlay")
  void browsePassesLocaleAndStatus() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(studio("trade-showcase")));

    List<Studio> studios = catalogService.browse("trades", "fr");

    assertThat(studios).singleElement().extracting(Studio::studioKey).isEqualTo("trade-showcase");
  }

  @Test
  @DisplayName("latestPublished skips drafts — a fresh install may only pin a published version")
  void latestPublishedSkipsDrafts() {
    when(jdbcTemplate.query(contains("studio_blueprint"), any(RowMapper.class), anyString()))
        .thenReturn(
            List.of(
                new BlueprintVersion("k", "1.1.0", BlueprintStatus.DRAFT, 90, null, null),
                new BlueprintVersion(
                    "k",
                    "1.0.0",
                    BlueprintStatus.PUBLISHED,
                    80,
                    "initial",
                    Instant.parse("2026-08-05T10:00:00Z"))));

    Optional<BlueprintVersion> latest = catalogService.latestPublished("k");

    assertThat(latest).isPresent();
    assertThat(latest.get().version()).isEqualTo("1.0.0");
  }

  @Test
  @DisplayName("latestPublished is empty for a studio whose versions are all drafts")
  void latestPublishedEmptyWhenOnlyDrafts() {
    when(jdbcTemplate.query(contains("studio_blueprint"), any(RowMapper.class), anyString()))
        .thenReturn(
            List.of(new BlueprintVersion("k", "1.0.0", BlueprintStatus.DRAFT, 0, null, null)));

    assertThat(catalogService.latestPublished("k")).isEmpty();
  }

  @Test
  @DisplayName("description tolerates a locale that carries none rather than returning null")
  void descriptionTolerantOfMissingLocale() {
    when(jdbcTemplate.query(
            contains("studio_i18n"), any(RowMapper.class), anyString(), anyString()))
        .thenReturn(java.util.Collections.singletonList(null));

    assertThat(catalogService.description("k", "de")).isEmpty();
  }
}
