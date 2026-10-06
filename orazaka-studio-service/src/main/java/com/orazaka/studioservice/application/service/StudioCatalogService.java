package com.orazaka.studioservice.application.service;

import com.orazaka.studio.domain.model.BlueprintStatus;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studio.domain.model.StudioPricing;
import com.orazaka.studio.domain.model.StudioStatus;
import com.orazaka.studioservice.domain.model.BlueprintVersion;
import com.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The Studio catalogue — what this platform can offer, localised.
 *
 * <p>Reads only. Whether a given actor may install what they are looking at is {@link
 * StudioAccessService}'s question, kept separate because the catalogue is <b>cosmetic</b>: locked
 * Studios are returned so the UI can grey them with an upsell, and hiding them destroys the funnel
 * (ADR-034 §4).
 *
 * <p>Localisation overlays {@code studio_i18n} in the same statement rather than a second round
 * trip, so a catalogue of forty Studios in two locales is still one query (ERR-109, no N+1).
 */
@Service
public class StudioCatalogService {

  private static final String SELECT_STUDIO =
      """
      SELECT s.studio_key, s.profession, s.icon_key, s.hero_asset_id, s.pricing, s.pack_key,
             s.entitlement_key, s.status, s.publisher_id, s.latest_version, s.updated_at,
             COALESCE(i.label, s.label)     AS effective_label,
             COALESCE(i.tagline, s.tagline) AS effective_tagline,
             i.description                  AS effective_description,
             (SELECT string_agg(locale, ',') FROM studio_i18n WHERE studio_key = s.studio_key)
                                            AS locales,
             -- The kind of the pack this Studio is sold under (ADR-061). No pack row reads as
             -- VERTICAL: the kind that needs an installation AND an entitlement, so a Studio that
             -- belongs to no pack is granted nothing it was not already granted.
             COALESCE(p.kind, 'VERTICAL')   AS kind
        FROM studio s
        LEFT JOIN studio_i18n i ON i.studio_key = s.studio_key AND i.locale = ?
        LEFT JOIN pack p ON p.pack_key = s.pack_key
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ColumnValueResolver columns;

  public StudioCatalogService(JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver required");
  }

  /**
   * The published catalogue, optionally narrowed to one profession.
   *
   * @param profession the métier to filter on, or {@code null} for everything
   * @param locale the caller's locale; rows without a translation fall back to the base row
   * @return the Studios, heaviest {@code sort_weight} first
   */
  public List<Studio> browse(String profession, String locale) {
    return jdbcTemplate.query(
        SELECT_STUDIO
            + " WHERE s.status = ? AND (CAST(? AS VARCHAR) IS NULL OR s.profession = ?)"
            + " ORDER BY s.sort_weight DESC, effective_label",
        (rs, rowNum) -> readStudio(rs),
        locale,
        StudioStatus.PUBLISHED.name(),
        profession,
        profession);
  }

  /**
   * One Studio by key, whatever its publication status.
   *
   * <p>Unpublished ones resolve on purpose: the admin console reads drafts through the same path,
   * and gating that is a {@code @PreAuthorize} concern on the controller, not a query concern here.
   *
   * @param studioKey the Studio's key
   * @param locale the caller's locale
   * @return the Studio, or empty when no such key exists
   */
  public Optional<Studio> find(String studioKey, String locale) {
    return jdbcTemplate
        .query(
            SELECT_STUDIO + " WHERE s.studio_key = ?",
            (rs, rowNum) -> readStudio(rs),
            locale,
            studioKey)
        .stream()
        .findFirst();
  }

  /**
   * The localised long description, which only the detail screen needs.
   *
   * @param studioKey the Studio's key
   * @param locale the caller's locale
   * @return the description, or empty when the locale carries none
   */
  public Optional<String> description(String studioKey, String locale) {
    return jdbcTemplate
        .query(
            "SELECT description FROM studio_i18n WHERE studio_key = ? AND locale = ?",
            (rs, rowNum) -> rs.getString("description"),
            studioKey,
            locale)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * A Studio's version history, newest first.
   *
   * @param studioKey the Studio's key
   * @return every version, including drafts — the admin console and the upgrade banner read the
   *     same list, and filtering here would need a second query for the other caller
   */
  public List<BlueprintVersion> versions(String studioKey) {
    return jdbcTemplate.query(
        "SELECT studio_key, version, status, estimated_credits, changelog, published_at"
            + " FROM studio_blueprint WHERE studio_key = ?"
            + " ORDER BY published_at DESC NULLS FIRST, version DESC",
        (rs, rowNum) ->
            new BlueprintVersion(
                rs.getString("studio_key"),
                rs.getString("version"),
                BlueprintStatus.valueOf(rs.getString("status")),
                rs.getLong("estimated_credits"),
                rs.getString("changelog"),
                columns.instantAt(rs, "published_at")),
        studioKey);
  }

  /**
   * The version a fresh installation would pin, and that the detail screen prices.
   *
   * @param studioKey the Studio's key
   * @return the newest published version, or empty when the Studio has none yet
   */
  public Optional<BlueprintVersion> latestPublished(String studioKey) {
    return versions(studioKey).stream()
        .filter(version -> version.status() == BlueprintStatus.PUBLISHED)
        .findFirst();
  }

  /**
   * The run-form schema of one version.
   *
   * @param studioKey the Studio's key
   * @param version the blueprint version
   * @return the raw JSON Schema, or empty when that version does not exist
   */
  public Optional<String> inputSchema(String studioKey, String version) {
    return jdbcTemplate
        .query(
            "SELECT input_schema FROM studio_blueprint WHERE studio_key = ? AND version = ?",
            (rs, rowNum) -> rs.getString("input_schema"),
            studioKey,
            version)
        .stream()
        .findFirst();
  }

  /**
   * The install-time config schema of one version.
   *
   * @param studioKey the Studio's key
   * @param version the blueprint version
   * @return the raw JSON Schema, or empty when that version does not exist
   */
  public Optional<String> configSchema(String studioKey, String version) {
    return jdbcTemplate
        .query(
            "SELECT config_schema FROM studio_blueprint WHERE studio_key = ? AND version = ?",
            (rs, rowNum) -> rs.getString("config_schema"),
            studioKey,
            version)
        .stream()
        .findFirst();
  }

  private Studio readStudio(ResultSet rs) throws SQLException {
    String locales = rs.getString("locales");
    return new Studio(
        rs.getString("studio_key"),
        rs.getString("effective_label"),
        rs.getString("effective_tagline"),
        rs.getString("profession"),
        rs.getString("icon_key"),
        rs.getString("hero_asset_id"),
        StudioPricing.valueOf(rs.getString("pricing")),
        rs.getString("pack_key"),
        PackKind.valueOf(rs.getString("kind")),
        rs.getString("entitlement_key"),
        StudioStatus.valueOf(rs.getString("status")),
        rs.getString("publisher_id"),
        rs.getString("latest_version"),
        locales == null ? Set.of() : Set.of(locales.split(",")),
        columns.instantAt(rs, "updated_at"));
  }
}
