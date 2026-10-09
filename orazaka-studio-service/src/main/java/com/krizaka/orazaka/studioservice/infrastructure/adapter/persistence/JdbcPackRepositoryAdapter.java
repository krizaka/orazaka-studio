package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studio.domain.model.Pack;
import com.krizaka.orazaka.studio.domain.model.PackCategory;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.PackStatus;
import com.krizaka.orazaka.studio.domain.model.RegulatoryClass;
import com.krizaka.orazaka.studioservice.domain.port.PackRepository;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the Pack catalogue, overlaying its localisation and its bundle in the same statement.
 *
 * <p>Package-private adapter behind {@link PackRepository} (AGENTS.md §2): the catalogue service
 * depends on the port and never learns that a Pack's label lives in a second table and its Studios
 * in a third.
 *
 * <p>One query per page, not one per row (ERR-109, no N+1): the i18n overlay is a {@code LEFT JOIN}
 * and the bundle is an aggregated sub-select, so a catalogue of forty Packs in two locales is still
 * a single round trip.
 *
 * <p><b>The label fallback is three-deep and that is deliberate.</b> Unlike {@code studio}, the
 * {@code pack} table carries no base label — the design puts every string in {@code pack_i18n} — so
 * "fall back to the base row" has nothing to fall back to. The chain is: the requested locale, then
 * the Pack's lowest-sorting translation, then the key itself. The last rung renders a raw key,
 * which is ugly on purpose: an unlabelled Pack should be visibly wrong rather than invisible, and
 * it must not take the whole marketplace page down with a constructor failure. It cannot happen in
 * seeded data — {@code PackCoherenceRules} fails the build on a published Pack with no translation.
 */
@Component
class JdbcPackRepositoryAdapter implements PackRepository {

  private static final String SELECT_PACK =
      """
      SELECT p.pack_key, p.category_key, p.icon_key, p.hero_asset_id, p.regulatory_class, p.kind,
             p.status, p.sort_weight, p.updated_at,
             COALESCE(i.label,
                      (SELECT f.label FROM pack_i18n f WHERE f.pack_key = p.pack_key
                        ORDER BY f.locale LIMIT 1),
                      p.pack_key)            AS effective_label,
             COALESCE(i.tagline,
                      (SELECT f.tagline FROM pack_i18n f WHERE f.pack_key = p.pack_key
                        ORDER BY f.locale LIMIT 1)) AS effective_tagline,
             i.description                   AS effective_description,
             (SELECT string_agg(s.studio_key, ',' ORDER BY s.sort_weight DESC, s.studio_key)
                FROM pack_studio s WHERE s.pack_key = p.pack_key)  AS studio_keys,
             (SELECT string_agg(l.locale, ',') FROM pack_i18n l WHERE l.pack_key = p.pack_key)
                                             AS locales
        FROM pack p
        LEFT JOIN pack_i18n i ON i.pack_key = p.pack_key AND i.locale = ?
      """;

  private static final String SELECT_CATEGORY =
      """
      SELECT c.category_key, c.icon_key, c.sort_weight, c.is_active,
             COALESCE(i.label,
                      (SELECT f.label FROM pack_category_i18n f
                        WHERE f.category_key = c.category_key ORDER BY f.locale LIMIT 1),
                      c.category_key)  AS effective_label,
             i.description             AS effective_description
        FROM pack_category c
        LEFT JOIN pack_category_i18n i
               ON i.category_key = c.category_key AND i.locale = ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ColumnValueResolver columns;

  JdbcPackRepositoryAdapter(JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver required");
  }

  @Override
  public List<Pack> browse(String categoryKey, String locale) {
    return jdbcTemplate.query(
        SELECT_PACK
            + " WHERE p.status = ? AND (CAST(? AS VARCHAR) IS NULL OR p.category_key = ?)"
            + " ORDER BY p.sort_weight DESC, effective_label",
        (rs, rowNum) -> readPack(rs),
        locale,
        PackStatus.PUBLISHED.name(),
        categoryKey,
        categoryKey);
  }

  @Override
  public Optional<Pack> find(String packKey, String locale) {
    return jdbcTemplate
        .query(SELECT_PACK + " WHERE p.pack_key = ?", (rs, rowNum) -> readPack(rs), locale, packKey)
        .stream()
        .findFirst();
  }

  @Override
  public List<PackCategory> categories(String locale) {
    return jdbcTemplate.query(
        SELECT_CATEGORY + " WHERE c.is_active ORDER BY c.sort_weight DESC, effective_label",
        (rs, rowNum) -> readCategory(rs),
        locale);
  }

  private Pack readPack(ResultSet rs) throws SQLException {
    return new Pack(
        rs.getString("pack_key"),
        rs.getString("category_key"),
        rs.getString("effective_label"),
        rs.getString("effective_tagline"),
        rs.getString("effective_description"),
        rs.getString("icon_key"),
        rs.getString("hero_asset_id"),
        RegulatoryClass.valueOf(rs.getString("regulatory_class")),
        PackKind.valueOf(rs.getString("kind")),
        PackStatus.valueOf(rs.getString("status")),
        rs.getInt("sort_weight"),
        split(rs.getString("studio_keys")),
        Set.copyOf(split(rs.getString("locales"))),
        columns.instantAt(rs, "updated_at"));
  }

  private static PackCategory readCategory(ResultSet rs) throws SQLException {
    return new PackCategory(
        rs.getString("category_key"),
        rs.getString("effective_label"),
        rs.getString("effective_description"),
        rs.getString("icon_key"),
        rs.getInt("sort_weight"),
        rs.getBoolean("is_active"));
  }

  /** {@code string_agg} yields NULL for an empty set, which is an empty bundle, not an error. */
  private static List<String> split(String aggregated) {
    return aggregated == null || aggregated.isBlank() ? List.of() : List.of(aggregated.split(","));
  }
}
