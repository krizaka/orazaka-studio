package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studio.domain.model.LocalisedText;
import com.krizaka.orazaka.studio.domain.model.PackBlueprint;
import com.krizaka.orazaka.studio.domain.model.PackBundle;
import com.krizaka.orazaka.studio.domain.model.PackCatalogEntry;
import com.krizaka.orazaka.studio.domain.model.PackSafety;
import com.krizaka.orazaka.studio.domain.model.PackScopeGuard;
import com.krizaka.orazaka.studio.domain.model.PackStudio;
import com.krizaka.orazaka.studio.domain.model.PackTranslation;
import com.krizaka.orazaka.studioservice.domain.port.PackInstallRepository;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a bundle's catalogue slice — seven tables, one transaction.
 *
 * <p>Package-private adapter behind {@link PackInstallRepository} (AGENTS.md §2). The
 * {@code @Transactional} is here rather than only on the service because this is the boundary where
 * "or nothing" is actually enforceable: everything below is one datasource, and a caller that
 * reached past the port could otherwise write four tables of seven.
 *
 * <p><b>Statement order is dependency order, not table order.</b> A category before the pack that
 * shelves it, the pack before the studios it bundles, a studio before its blueprint and before the
 * {@code pack_studio} row that joins them. Postgres would enforce most of it through the foreign
 * keys; writing it in the order the constraints imply means the failure, when it comes, names the
 * row that is actually missing rather than the last one attempted.
 *
 * <p><b>Every statement is an upsert.</b> Re-installing a bundle must be safe — it is how a failed
 * install is retried — and {@code ON CONFLICT DO NOTHING}, which the seeds use, is the wrong
 * primitive here: a seed wants "leave what is there", an installer wants "make it match the
 * manifest". The one exception is {@code studio_blueprint}, where {@code DO NOTHING} is correct
 * because a PUBLISHED version is immutable by trigger and shipping a change means shipping a new
 * version (ADR-034 §5).
 */
@Component
class JdbcPackInstallRepositoryAdapter implements PackInstallRepository {

  private static final Logger logger =
      LoggerFactory.getLogger(JdbcPackInstallRepositoryAdapter.class);

  private static final String UPSERT_CATEGORY =
      """
      INSERT INTO pack_category (category_key, icon_key, sort_weight, is_active)
      VALUES (?, ?, ?, TRUE)
      ON CONFLICT (category_key) DO NOTHING
      """;

  private static final String UPSERT_CATEGORY_I18N =
      """
      INSERT INTO pack_category_i18n (category_key, locale, label, description)
      VALUES (?, ?, ?, ?)
      ON CONFLICT (category_key, locale) DO NOTHING
      """;

  private static final String UPSERT_PACK =
      """
      INSERT INTO pack (pack_key, category_key, icon_key, regulatory_class, kind, scope_guard,
                        consent_version, consent_statement, safety, status,
                        sort_weight, updated_at)
      VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, now())
      ON CONFLICT (pack_key) DO UPDATE SET
        category_key = EXCLUDED.category_key, icon_key = EXCLUDED.icon_key,
        regulatory_class = EXCLUDED.regulatory_class, kind = EXCLUDED.kind,
        scope_guard = EXCLUDED.scope_guard,
        consent_version = EXCLUDED.consent_version,
        consent_statement = EXCLUDED.consent_statement, safety = EXCLUDED.safety,
        status = EXCLUDED.status,
        sort_weight = EXCLUDED.sort_weight, updated_at = now()
      """;

  private static final String UPSERT_PACK_I18N =
      """
      INSERT INTO pack_i18n (pack_key, locale, label, tagline, description)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (pack_key, locale) DO UPDATE SET
        label = EXCLUDED.label, tagline = EXCLUDED.tagline, description = EXCLUDED.description
      """;

  private static final String UPSERT_STUDIO =
      """
      INSERT INTO studio (studio_key, label, tagline, profession, icon_key, pricing, pack_key,
                          entitlement_key, status, publisher_id, latest_version, sort_weight,
                          updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
      ON CONFLICT (studio_key) DO UPDATE SET
        label = EXCLUDED.label, tagline = EXCLUDED.tagline, profession = EXCLUDED.profession,
        icon_key = EXCLUDED.icon_key, pricing = EXCLUDED.pricing, pack_key = EXCLUDED.pack_key,
        entitlement_key = EXCLUDED.entitlement_key, status = EXCLUDED.status,
        publisher_id = EXCLUDED.publisher_id, latest_version = EXCLUDED.latest_version,
        sort_weight = EXCLUDED.sort_weight, updated_at = now()
      """;

  private static final String UPSERT_STUDIO_I18N =
      """
      INSERT INTO studio_i18n (studio_key, locale, label, tagline, description)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (studio_key, locale) DO UPDATE SET
        label = EXCLUDED.label, tagline = EXCLUDED.tagline, description = EXCLUDED.description
      """;

  private static final String UPSERT_PACK_STUDIO =
      """
      INSERT INTO pack_studio (pack_key, studio_key, sort_weight)
      VALUES (?, ?, ?)
      ON CONFLICT (pack_key, studio_key) DO UPDATE SET sort_weight = EXCLUDED.sort_weight
      """;

  private static final String INSERT_BLUEPRINT =
      """
      INSERT INTO studio_blueprint (studio_key, version, status, definition, input_schema,
                                    config_schema, estimated_credits, changelog, published_at,
                                    created_by)
      VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, CASE WHEN ? = 'PUBLISHED' THEN now() END, ?)
      ON CONFLICT (studio_key, version) DO NOTHING
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ColumnValueResolver columns;

  JdbcPackInstallRepositoryAdapter(JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver cannot be null");
  }

  /** The declared scope guard as the JSONB the pack row stores, or null when there is none. */
  private String scopeGuardJson(PackBundle bundle) {
    PackScopeGuard guard = bundle.scopeGuard();
    return guard == null
        ? null
        : columns.json(Map.of("refusedTerms", guard.refusedTerms(), "refusal", guard.refusal()));
  }

  /**
   * The pack's crisis declaration as the dispatcher reads it back.
   *
   * <p>Resources keep their source and verification date all the way into the row, so an operator
   * asking "where did this number come from and when was it last checked" reads it out of the
   * database rather than out of a pack directory that may have been replaced (ADR-055 §5).
   */
  private String safetyJson(PackBundle bundle) {
    PackSafety safety = bundle.safety();
    if (safety == null) {
      return null;
    }
    Map<String, Object> resources = new LinkedHashMap<>();
    safety
        .resources()
        .forEach(
            (region, resource) ->
                resources.put(
                    region,
                    Map.of(
                        "region", resource.region(),
                        "label", resource.label(),
                        "contact", resource.contact(),
                        "availability", resource.availability(),
                        "sourceUrl", resource.sourceUrl(),
                        "verifiedOn", resource.verifiedOn().toString())));
    return columns.json(
        Map.of(
            "crisisTerms", safety.crisisTerms(),
            "response", safety.response(),
            "safetyReviewRef", safety.safetyReviewRef(),
            "resources", resources));
  }

  @Override
  @Transactional
  public int apply(PackBundle bundle) {
    Objects.requireNonNull(bundle, "bundle must not be null");
    if (bundle.isCatalogued()) {
      writeCategory(bundle);
      writePack(bundle);
    }
    for (PackStudio studio : bundle.studios()) {
      writeStudio(bundle, studio);
    }
    logger.info(
        "Applied bundle {} v{}: {} studio(s){}",
        bundle.key(),
        bundle.version(),
        bundle.studios().size(),
        bundle.isCatalogued() ? " under pack " + bundle.key() : " (no pack row)");
    return bundle.studios().size();
  }

  @Override
  @Transactional
  public boolean remove(String bundleKey) {
    if (bundleKey == null || bundleKey.isBlank()) {
      return false;
    }
    // studio rows first: pack_studio cascades from both sides, and deleting the pack first would
    // leave the studios of a catalogued bundle orphaned rather than removed.
    int studios =
        jdbcTemplate.update(
            "DELETE FROM studio WHERE studio_key IN"
                + " (SELECT studio_key FROM pack_studio WHERE pack_key = ?) OR studio_key = ?",
            bundleKey,
            bundleKey);
    int packs = jdbcTemplate.update("DELETE FROM pack WHERE pack_key = ?", bundleKey);
    return studios + packs > 0;
  }

  @Override
  public boolean isApplied(PackBundle bundle) {
    Objects.requireNonNull(bundle, "bundle must not be null");
    // The join is the point: studio_blueprint keeps every version a Studio ever had, and a row
    // there whose studio has since been removed would report an installed bundle with nothing to
    // run. Both halves, or the bundle is not carried.
    for (PackStudio studio : bundle.studios()) {
      Integer carried =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM studio_blueprint b JOIN studio s USING (studio_key)"
                  + " WHERE b.studio_key = ? AND b.version = ?",
              Integer.class,
              studio.key(),
              studio.blueprint().version());
      if (carried == null || carried == 0) {
        return false;
      }
    }
    return true;
  }

  private void writeCategory(PackBundle bundle) {
    PackCatalogEntry catalog = bundle.catalog();
    if (catalog.category() == null) {
      return;
    }
    // DO NOTHING, not DO UPDATE: a bundle may OPEN a shelf, never restyle one that other packs
    // are already browsed under. The first pack on a shelf names it; the fourth does not rename it.
    jdbcTemplate.update(
        UPSERT_CATEGORY,
        catalog.categoryKey(),
        catalog.category().iconKey(),
        catalog.category().sortWeight());
    bundle
        .translations()
        .forEach(
            (locale, translation) -> {
              if (translation.category() != null) {
                jdbcTemplate.update(
                    UPSERT_CATEGORY_I18N,
                    catalog.categoryKey(),
                    locale,
                    translation.category().label(),
                    translation.category().description());
              }
            });
  }

  private void writePack(PackBundle bundle) {
    PackCatalogEntry catalog = bundle.catalog();
    jdbcTemplate.update(
        UPSERT_PACK,
        bundle.key(),
        catalog.categoryKey(),
        catalog.iconKey(),
        bundle.regulatoryClass().name(),
        // Declared by the manifest and written as it was declared — never inferred from the tier,
        // the class, the shelf or the key (ADR-061). The CHECKs on the table refuse the one
        // combination a TOOLKIT cannot take, whatever wrote this row.
        bundle.kind().name(),
        // The declared domain travels with the pack row: the dispatcher reads it per run, and a
        // pack that changes what it refuses does so by re-installing, never by a live edit.
        scopeGuardJson(bundle),
        bundle.consent() == null ? null : bundle.consent().version(),
        bundle.consent() == null ? null : bundle.consent().statement(),
        safetyJson(bundle),
        catalog.status().name(),
        catalog.sortWeight());
    bundle
        .translations()
        .forEach(
            (locale, translation) -> {
              if (translation.pack() != null) {
                jdbcTemplate.update(
                    UPSERT_PACK_I18N,
                    bundle.key(),
                    locale,
                    translation.pack().label(),
                    translation.pack().tagline(),
                    translation.pack().description());
              }
            });
  }

  private void writeStudio(PackBundle bundle, PackStudio studio) {
    LocalisedText base = baseText(bundle.translations(), studio.key());
    PackBlueprint blueprint = studio.blueprint();
    jdbcTemplate.update(
        UPSERT_STUDIO,
        studio.key(),
        base.label(),
        base.tagline(),
        studio.profession(),
        studio.iconKey(),
        studio.pricing().name(),
        bundle.isCatalogued() ? bundle.key() : null,
        studio.entitlementKey(),
        studio.status().name(),
        studio.publisherId(),
        blueprint.version(),
        studio.sortWeight());
    bundle
        .translations()
        .forEach(
            (locale, translation) -> {
              LocalisedText text = translation.studios().get(studio.key());
              if (text != null) {
                jdbcTemplate.update(
                    UPSERT_STUDIO_I18N,
                    studio.key(),
                    locale,
                    text.label(),
                    text.tagline(),
                    text.description());
              }
            });
    int written =
        jdbcTemplate.update(
            INSERT_BLUEPRINT,
            studio.key(),
            blueprint.version(),
            blueprint.status().name(),
            blueprint.definition(),
            blueprint.inputSchema(),
            blueprint.configSchema(),
            blueprint.estimatedCredits(),
            blueprint.changelog(),
            blueprint.status().name(),
            blueprint.createdBy());
    if (written == 0) {
      // The immutability above is right; the silence was not. A pack author who edits a blueprint
      // and reinstalls at the same version gets "Installed" and runs the OLD definition, with
      // nothing anywhere saying so. Keeping the stored version is still the behaviour — this only
      // stops it being invisible (ADR-052 §6).
      logger.warn(
          "Kept the stored blueprint for studio {} version {}: a PUBLISHED version is immutable,"
              + " so the definition just shipped was NOT applied. Ship a new version to change it.",
          studio.key(),
          blueprint.version());
    }
    if (bundle.isCatalogued()) {
      jdbcTemplate.update(UPSERT_PACK_STUDIO, bundle.key(), studio.key(), studio.sortWeight());
    }
  }

  /**
   * The strings written to the {@code studio} row itself, which the UI falls back to when a locale
   * has no translation.
   *
   * <p>Prefers French, then any locale the bundle ships, then the key. The preference is not
   * arbitrary: the base row is what a caller with an unknown locale sees, and the product ships in
   * French first. A bundle shipping no translation at all writes its key, which renders visibly
   * wrong rather than blank — the same choice the read adapter's three-deep fallback makes.
   */
  private static LocalisedText baseText(Map<String, PackTranslation> translations, String key) {
    PackTranslation preferred = translations.get("fr");
    if (preferred != null && preferred.studios().containsKey(key)) {
      return preferred.studios().get(key);
    }
    return translations.values().stream()
        .map(translation -> translation.studios().get(key))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(new LocalisedText(key, null, null));
  }
}
