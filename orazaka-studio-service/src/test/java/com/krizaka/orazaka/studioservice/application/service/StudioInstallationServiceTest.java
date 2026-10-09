package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import com.krizaka.orazaka.studio.domain.model.StudioStatus;
import com.krizaka.orazaka.studioservice.domain.exception.ConsentRequiredException;
import com.krizaka.orazaka.studioservice.domain.exception.InstallationNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.BlueprintVersion;
import com.krizaka.orazaka.studioservice.domain.model.InstallConsent;
import com.krizaka.orazaka.studioservice.domain.model.InstalledStudio;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class StudioInstallationServiceTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final String STUDIO_KEY = "trade-showcase";
  private static final UUID INSTALLATION = UUID.fromString("9f1c0a10-0000-4000-8000-000000000010");
  private static final String CONFIG_SCHEMA =
      "{\"tone\":{\"type\":\"string\"},\"brandName\":{\"type\":\"string\"}}";

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final StudioCatalogService catalogService = mock(StudioCatalogService.class);
  private final StudioAccessService accessService = mock(StudioAccessService.class);
  private final StudioInstallationService installationService =
      new StudioInstallationService(
          jdbcTemplate,
          catalogService,
          accessService,
          new ObjectMapper(),
          new ColumnValueResolver(new ObjectMapper()));

  private static Studio studio() {
    return new Studio(
        STUDIO_KEY,
        "Vitrine Artisan",
        null,
        "trades",
        "studio",
        null,
        StudioPricing.FREE,
        null,
        PackKind.VERTICAL,
        "studio." + STUDIO_KEY,
        StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        Set.of(),
        Instant.parse("2026-08-05T10:00:00Z"));
  }

  private static InstalledStudio installed(String pinned, String latest) {
    return new InstalledStudio(
        INSTALLATION,
        STUDIO_KEY,
        "Vitrine Artisan",
        "studio",
        pinned,
        latest,
        InstallationStatus.ACTIVE,
        Map.of(),
        Instant.parse("2026-08-05T10:00:00Z"),
        null);
  }

  private void catalogueHas(String version) {
    when(catalogService.find(eq(STUDIO_KEY), anyString())).thenReturn(Optional.of(studio()));
    when(catalogService.latestPublished(STUDIO_KEY))
        .thenReturn(
            Optional.of(
                new BlueprintVersion(
                    STUDIO_KEY, version, BlueprintStatus.PUBLISHED, 80, null, null)));
    when(catalogService.configSchema(STUDIO_KEY, version)).thenReturn(Optional.of(CONFIG_SCHEMA));
  }

  @Test
  @DisplayName("Install pins the newest published version and writes the row")
  void installPinsLatestPublished() {
    catalogueHas("1.0.0");
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation ->
                invocation.getArgument(0, String.class).contains("regulatory_class")
                    ? java.util.List.of()
                    : java.util.List.of(installed("1.0.0", "1.0.0")));

    InstalledStudio result =
        installationService.install(STUDIO_KEY, ACTOR, Map.of("tone", "premium"), "fr");

    assertThat(result.pinnedVersion()).isEqualTo("1.0.0");
    verify(jdbcTemplate)
        .update(
            anyString(),
            eq(ACTOR),
            eq(STUDIO_KEY),
            eq("1.0.0"),
            eq("ACTIVE"),
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            isNull());
  }

  @Test
  @DisplayName("Install refuses an unknown studio before touching entitlement or the database")
  void installRejectsUnknownStudio() {
    when(catalogService.find(eq("nope"), anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> installationService.install("nope", ACTOR, Map.of(), "fr"))
        .isInstanceOf(StudioNotFoundException.class);
    verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
  }

  @Test
  @DisplayName("Install enforces entitlement authoritatively — the catalogue's grey is cosmetic")
  void installEnforcesEntitlement() {
    catalogueHas("1.0.0");
    doThrow(new StudioNotEntitledException(STUDIO_KEY, "studio.x", "pkg"))
        .when(accessService)
        .requireEntitled(any(), eq(ACTOR));

    assertThatThrownBy(() -> installationService.install(STUDIO_KEY, ACTOR, Map.of(), "fr"))
        .isInstanceOf(StudioNotEntitledException.class);
    verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
  }

  @Test
  @DisplayName("Install rejects a config key the pinned version does not declare")
  void installRejectsUnknownConfigKey() {
    catalogueHas("1.0.0");

    assertThatThrownBy(
            () -> installationService.install(STUDIO_KEY, ACTOR, Map.of("nonsense", "x"), "fr"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonsense");
  }

  @Test
  @DisplayName("Install accepts every key the schema declares")
  void installAcceptsDeclaredKeys() {
    catalogueHas("1.0.0");
    // Two queries now run on this path: the regulatory requirements of the Studio's pack, and the
    // read-back. They are told apart by their SQL rather than by call order, so a reordering of
    // the service does not silently make this test assert nothing.
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation ->
                invocation.getArgument(0, String.class).contains("regulatory_class")
                    ? java.util.List.of()
                    : java.util.List.of(installed("1.0.0", "1.0.0")));

    installationService.install(
        STUDIO_KEY, ACTOR, Map.of("tone", "premium", "brandName", "Dupont"), "fr");

    verify(jdbcTemplate)
        .update(
            anyString(),
            eq(ACTOR),
            eq(STUDIO_KEY),
            eq("1.0.0"),
            eq("ACTIVE"),
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            isNull());
  }

  @Test
  @DisplayName("Install refuses a studio with nothing published to pin")
  void installRefusesUnpublishedStudio() {
    when(catalogService.find(eq(STUDIO_KEY), anyString())).thenReturn(Optional.of(studio()));
    when(catalogService.latestPublished(STUDIO_KEY)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> installationService.install(STUDIO_KEY, ACTOR, Map.of(), "fr"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Updating the config of an installation the actor does not own is a not-found")
  void updateConfigScopedToOwner() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(java.util.List.of());

    assertThatThrownBy(
            () -> installationService.updateConfig(INSTALLATION, ACTOR, Map.of("tone", "x")))
        .isInstanceOf(InstallationNotFoundException.class);
  }

  @Test
  @DisplayName("Upgrading an installation the actor does not own is a not-found")
  void upgradeScopedToOwner() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(java.util.List.of());

    assertThatThrownBy(() -> installationService.upgrade(INSTALLATION, ACTOR))
        .isInstanceOf(InstallationNotFoundException.class);
  }

  @Test
  @DisplayName("Uninstall soft-revokes, scoped by actor, and reports whether it acted")
  void uninstallSoftRevokes() {
    when(jdbcTemplate.update(
            anyString(), eq("REVOKED"), eq(INSTALLATION), eq(ACTOR), eq("REVOKED")))
        .thenReturn(1);

    assertThat(installationService.uninstall(INSTALLATION, ACTOR)).isTrue();

    when(jdbcTemplate.update(
            anyString(), eq("REVOKED"), eq(INSTALLATION), eq(ACTOR), eq("REVOKED")))
        .thenReturn(0);

    assertThat(installationService.uninstall(INSTALLATION, ACTOR)).isFalse();
  }

  // ── ADR-055: the consent gate ─────────────────────────────────────────────

  private void packRequires(String regulatoryClass, String consentVersion, String regions) {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              if (!invocation.getArgument(0, String.class).contains("regulatory_class")) {
                return java.util.List.of(installed("1.0.0", "1.0.0"));
              }
              java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
              when(rs.getString("regulatory_class")).thenReturn(regulatoryClass);
              when(rs.getString("consent_version")).thenReturn(consentVersion);
              when(rs.getString("consent_statement")).thenReturn("Vous consentez au traitement…");
              when(rs.getString("safety")).thenReturn(regions);
              RowMapper<?> mapper = invocation.getArgument(1);
              return java.util.List.of(mapper.mapRow(rs, 0));
            });
  }

  private static final String SOURCED_FR_ONLY =
      "{\"resources\":{\"FR\":{\"region\":\"FR\",\"contact\":\"3114\"}}}";

  @Test
  @DisplayName("[ADR-055] a REGULATED pack does not install without consent — 451, with the text")
  void regulatedRefusesWithoutConsent() {
    catalogueHas("1.0.0");
    packRequires("REGULATED", "1.0", SOURCED_FR_ONLY);

    assertThatThrownBy(() -> installationService.install(STUDIO_KEY, ACTOR, Map.of(), "fr", null))
        .isInstanceOf(ConsentRequiredException.class)
        .extracting("requiredVersion", "statement")
        .containsExactly("1.0", "Vous consentez au traitement…");

    verify(jdbcTemplate, org.mockito.Mockito.never())
        .update(
            anyString(),
            eq(ACTOR),
            eq(STUDIO_KEY),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  @DisplayName("[ADR-055] consent to a superseded statement is no consent — the new one is asked")
  void aChangedStatementAsksAgain() {
    catalogueHas("1.0.0");
    packRequires("REGULATED", "2.0", SOURCED_FR_ONLY);

    assertThatThrownBy(
            () ->
                installationService.install(
                    STUDIO_KEY, ACTOR, Map.of(), "fr", new InstallConsent("1.0", true, "FR")))
        .isInstanceOf(ConsentRequiredException.class)
        .extracting("requiredVersion")
        .isEqualTo("2.0");
  }

  @Test
  @DisplayName("[ADR-055] no age attestation, no install")
  void refusesWithoutAnAgeAttestation() {
    catalogueHas("1.0.0");
    packRequires("REGULATED", "1.0", SOURCED_FR_ONLY);

    assertThatThrownBy(
            () ->
                installationService.install(
                    STUDIO_KEY, ACTOR, Map.of(), "fr", new InstallConsent("1.0", false, "FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not available to minors");
  }

  @Test
  @DisplayName("[ADR-055] a region the pack never sourced is a region it does not install in")
  void refusesAnUnsourcedRegion() {
    catalogueHas("1.0.0");
    packRequires("REGULATED", "1.0", SOURCED_FR_ONLY);

    // Not availability shrinkage for its own sake: a crisis response with no verified local
    // resource is a number the pack made up.
    assertThatThrownBy(
            () ->
                installationService.install(
                    STUDIO_KEY, ACTOR, Map.of(), "fr", new InstallConsent("1.0", true, "DE")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not available in region 'DE'");
  }

  @Test
  @DisplayName("[ADR-055] with all three declared, it installs and the consent is recorded")
  void installsWithConsent() {
    catalogueHas("1.0.0");
    packRequires("REGULATED", "1.0", SOURCED_FR_ONLY);

    installationService.install(
        STUDIO_KEY, ACTOR, Map.of(), "fr", new InstallConsent("1.0", true, "FR"));

    verify(jdbcTemplate)
        .update(
            anyString(),
            eq(ACTOR),
            eq(STUDIO_KEY),
            eq("1.0.0"),
            eq("ACTIVE"),
            anyString(),
            eq("1.0"),
            org.mockito.ArgumentMatchers.notNull(),
            org.mockito.ArgumentMatchers.notNull(),
            eq("FR"));
  }

  @Test
  @DisplayName("a STANDARD pack is unaffected: the gate belongs to the regulatory class")
  void standardPacksAreUnaffected() {
    catalogueHas("1.0.0");
    packRequires("STANDARD", null, null);

    installationService.install(STUDIO_KEY, ACTOR, Map.of(), "fr", null);

    verify(jdbcTemplate)
        .update(
            anyString(),
            eq(ACTOR),
            eq(STUDIO_KEY),
            eq("1.0.0"),
            eq("ACTIVE"),
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            isNull());
  }
}
