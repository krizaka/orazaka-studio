package com.orazaka.studioservice.application.service;

import com.orazaka.studio.domain.model.InstallationStatus;
import com.orazaka.studio.domain.model.PackKind;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studioservice.domain.exception.ConsentRequiredException;
import com.orazaka.studioservice.domain.exception.InstallationNotFoundException;
import com.orazaka.studioservice.domain.exception.StudioIncludedException;
import com.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.orazaka.studioservice.domain.model.BlueprintVersion;
import com.orazaka.studioservice.domain.model.InstallConsent;
import com.orazaka.studioservice.domain.model.InstalledStudio;
import com.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Installing, configuring and uninstalling a Studio for one actor.
 *
 * <p>Every statement is scoped by {@code actor_id}. That is the whole tenant-isolation story and it
 * is deliberately not expressible as a URL rule: "your own installation" is a row predicate, so a
 * lookup that misses answers {@link InstallationNotFoundException} whether the row is absent or
 * simply somebody else's (ADR-034 §18).
 *
 * <p>Nothing here deletes. An uninstall soft-revokes so that reinstalling restores the actor's
 * configuration instead of asking them to fill the install dialog in again.
 */
@Service
public class StudioInstallationService {

  private static final String SELECT_INSTALLED =
      """
      SELECT i.id, i.studio_key, i.pinned_version, i.status, i.config, i.installed_at,
             i.last_run_at, s.label, s.icon_key, s.latest_version
        FROM studio_installation i
        JOIN studio s ON s.studio_key = i.studio_key
       WHERE i.actor_id = ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final StudioCatalogService catalogService;
  private final StudioAccessService accessService;
  private final ObjectMapper objectMapper;
  private final ColumnValueResolver columns;

  public StudioInstallationService(
      JdbcTemplate jdbcTemplate,
      StudioCatalogService catalogService,
      StudioAccessService accessService,
      ObjectMapper objectMapper,
      ColumnValueResolver columns) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.catalogService = Objects.requireNonNull(catalogService, "StudioCatalogService required");
    this.accessService = Objects.requireNonNull(accessService, "StudioAccessService required");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver required");
  }

  /**
   * Installs a Studio for an actor, pinning its newest published version.
   *
   * <p>Re-installing a revoked Studio reactivates the existing row rather than inserting a second
   * one: the unique key is {@code (actor_id, studio_key)}, and restoring the previous configuration
   * is the point of a soft revoke.
   *
   * @param studioKey the Studio to install
   * @param actorId the opaque billable subject
   * @param config the actor's answers to the config schema
   * @param locale the caller's locale, for the label carried back
   * @return the installation as stored
   * @throws StudioNotFoundException when the key names nothing
   * @throws com.orazaka.studio.domain.exception.StudioNotEntitledException when the actor may not
   *     install it — carrying the pack to buy so the refusal opens checkout
   */
  @Transactional
  public InstalledStudio install(
      String studioKey, String actorId, Map<String, String> config, String locale) {
    return install(studioKey, actorId, config, locale, null);
  }

  /**
   * Installs a Studio, enforcing whatever its pack's regulatory class requires.
   *
   * <p>For {@code REGULATED} this is the blocking half of ADR-055: no consent to the statement
   * currently in force, no installation. Not a warning and not a banner — health data is a special
   * category under GDPR Art. 9 and Loi 25, and the lawful basis has to exist before the processing
   * does, not alongside it.
   *
   * @param studioKey the Studio
   * @param actorId the opaque billable subject
   * @param config install-time configuration
   * @param locale for the catalogue lookup
   * @param consent what the caller declared; {@code null} for a pack that requires none
   * @return the installation
   * @throws ConsentRequiredException when a REGULATED pack has no matching consent recorded
   */
  public InstalledStudio install(
      String studioKey,
      String actorId,
      Map<String, String> config,
      String locale,
      InstallConsent consent) {
    Studio studio =
        catalogService
            .find(studioKey, locale)
            .orElseThrow(() -> new StudioNotFoundException(studioKey));

    // Authoritative here, cosmetic in the catalogue — the same decision, read twice (§8.3).
    accessService.requireEntitled(studio, actorId);
    // ADR-061. A TOOLKIT's installation is derived from the entitlement just checked, so there is
    // nothing to create. Refused rather than acknowledged: a success would have to return an id, a
    // pin and a config that do not exist, and the caller's next call would fail far from here.
    // After the entitlement check, so an actor who cannot have it hears that first, as for any
    // Studio.
    if (studio.kind() == PackKind.TOOLKIT) {
      throw new StudioIncludedException(
          studio.studioKey(), studio.entitlementKey(), studio.packKey());
    }

    BlueprintVersion pinned =
        catalogService
            .latestPublished(studioKey)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "studio has no published version to pin: " + studioKey));

    Map<String, String> validated = validateConfig(studioKey, pinned.version(), config);
    RegulatoryRequirements required = requirementsOf(studioKey);
    required.check(studioKey, consent);

    jdbcTemplate.update(
        "INSERT INTO studio_installation (actor_id, studio_key, pinned_version, status, config,"
            + " consent_version, consent_recorded_at, age_attested_at, region)"
            + " VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)"
            + " ON CONFLICT (actor_id, studio_key) DO UPDATE"
            + " SET status = EXCLUDED.status, pinned_version = EXCLUDED.pinned_version,"
            + "     config = EXCLUDED.config, consent_version = EXCLUDED.consent_version,"
            + "     consent_recorded_at = EXCLUDED.consent_recorded_at,"
            + "     age_attested_at = EXCLUDED.age_attested_at, region = EXCLUDED.region",
        actorId,
        studioKey,
        pinned.version(),
        InstallationStatus.ACTIVE.name(),
        columns.json(validated),
        consent == null ? null : consent.consentVersion(),
        // Computed here rather than in a CASE over a bare parameter: Postgres cannot infer the
        // type of `?` inside CASE, and the statement failed to parse at all — which is worse than
        // wrong, because it failed for every install and not only for a REGULATED one.
        consent == null ? null : java.sql.Timestamp.from(java.time.Instant.now()),
        consent != null && consent.ageAttested()
            ? java.sql.Timestamp.from(java.time.Instant.now())
            : null,
        consent == null ? null : consent.region());

    return findByStudioKey(studioKey, actorId).orElseThrow();
  }

  /**
   * What this Studio's pack requires before it may be installed, read from the pack row.
   *
   * <p>Read per install rather than cached: a pack is re-installed to bump its consent version, and
   * a cache would keep letting people in on the old statement for as long as it lived.
   */
  private RegulatoryRequirements requirementsOf(String studioKey) {
    return jdbcTemplate
        .query(
            """
            SELECT p.regulatory_class, p.consent_version, p.consent_statement, p.safety
              FROM pack_studio ps JOIN pack p ON p.pack_key = ps.pack_key
             WHERE ps.studio_key = ?
            """,
            (rs, rowNum) ->
                new RegulatoryRequirements(
                    rs.getString("regulatory_class"),
                    rs.getString("consent_version"),
                    rs.getString("consent_statement"),
                    availableRegions(rs.getString("safety"))),
            studioKey)
        .stream()
        .findFirst()
        .orElse(RegulatoryRequirements.NONE);
  }

  /** The regions a pack declared a verified crisis resource for; empty when it declared none. */
  private Set<String> availableRegions(String safetyJson) {
    if (safetyJson == null || safetyJson.isBlank()) {
      return Set.of();
    }
    Map<String, Object> safety = columns.objectMap(safetyJson);
    Object resources = safety.get("resources");
    return resources instanceof Map<?, ?> map
        ? map.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet())
        : Set.of();
  }

  /**
   * A pack's install-time obligations, and the one method that enforces them.
   *
   * @param regulatoryClass the class the pack declared
   * @param consentVersion the statement version now in force, {@code null} below REGULATED
   * @param consentStatement what the user must agree to
   * @param availableRegions the regions with a verified crisis resource
   */
  private record RegulatoryRequirements(
      String regulatoryClass,
      String consentVersion,
      String consentStatement,
      Set<String> availableRegions) {

    static final RegulatoryRequirements NONE =
        new RegulatoryRequirements("STANDARD", null, null, Set.of());

    /** Whether this pack is in the class that adds consent and crisis handling. */
    boolean isRegulated() {
      return "REGULATED".equals(regulatoryClass);
    }

    /**
     * Refuses an install that does not carry what this class requires.
     *
     * @param studioKey the Studio, for the message
     * @param consent what the caller declared
     */
    void check(String studioKey, InstallConsent consent) {
      if (!isRegulated()) {
        return;
      }
      if (consent == null || !Objects.equals(consentVersion, consent.consentVersion())) {
        throw new ConsentRequiredException(studioKey, consentVersion, consentStatement);
      }
      if (!consent.ageAttested()) {
        // Self-declared, and weak. It is the only evidence available without collecting a birth
        // date from every user of the platform for one pack (ADR-055 §6) — and an unasked
        // question is weaker still.
        throw new IllegalArgumentException(
            "installing "
                + studioKey
                + " requires an age attestation: this pack is not available"
                + " to minors");
      }
      if (consent.region() == null || !availableRegions.contains(consent.region())) {
        // Not availability shrinkage for its own sake: a crisis response with no verified local
        // resource is a number this pack made up, and the whole control turns on not doing that.
        throw new IllegalArgumentException(
            "installing "
                + studioKey
                + " is not available in region '"
                + consent.region()
                + "': this pack ships a verified crisis resource for "
                + availableRegions
                + " and nowhere else");
      }
    }
  }

  /**
   * Everything this actor has installed, minus what they uninstalled.
   *
   * @param actorId the opaque billable subject
   * @return their installations, most recently installed first
   */
  public List<InstalledStudio> listFor(String actorId) {
    return jdbcTemplate.query(
        SELECT_INSTALLED + " AND i.status <> ? ORDER BY i.installed_at DESC",
        (rs, rowNum) -> readInstalled(rs),
        actorId,
        InstallationStatus.REVOKED.name());
  }

  /**
   * One installation, scoped to its owner.
   *
   * @param installationId the installation
   * @param actorId the opaque billable subject
   * @return the installation, or empty when the id names nothing this actor owns
   */
  public Optional<InstalledStudio> find(UUID installationId, String actorId) {
    return jdbcTemplate
        .query(
            SELECT_INSTALLED + " AND i.id = ?",
            (rs, rowNum) -> readInstalled(rs),
            actorId,
            installationId)
        .stream()
        .findFirst();
  }

  /**
   * This actor's live installation of one Studio, looked up by the Studio rather than by its id.
   *
   * <p>What the run-by-Studio entry needs to reach a VERTICAL the same way the run-by-installation
   * entry does (ADR-061). A revoked installation is an uninstalled one — uninstall soft-revokes —
   * so it is not returned, exactly as {@link #listFor} does not list it. A paused one is returned,
   * and the run path refuses it for the same reason it refuses it by id.
   *
   * @param studioKey the Studio
   * @param actorId the opaque billable subject
   * @return the installation, or empty when this actor has not installed that Studio
   */
  public Optional<InstalledStudio> findForStudio(String studioKey, String actorId) {
    return jdbcTemplate
        .query(
            SELECT_INSTALLED + " AND i.studio_key = ? AND i.status <> ?",
            (rs, rowNum) -> readInstalled(rs),
            actorId,
            studioKey,
            InstallationStatus.REVOKED.name())
        .stream()
        .findFirst();
  }

  /**
   * Replaces an installation's configuration.
   *
   * @param installationId the installation
   * @param actorId the opaque billable subject
   * @param config the new answers, validated against the pinned version's schema
   * @return the installation as stored
   * @throws InstallationNotFoundException when the id names nothing this actor owns
   */
  @Transactional
  public InstalledStudio updateConfig(
      UUID installationId, String actorId, Map<String, String> config) {
    InstalledStudio installation =
        find(installationId, actorId)
            .orElseThrow(() -> new InstallationNotFoundException(installationId));

    Map<String, String> validated =
        validateConfig(installation.studioKey(), installation.pinnedVersion(), config);

    jdbcTemplate.update(
        "UPDATE studio_installation SET config = ?::jsonb WHERE id = ? AND actor_id = ?",
        columns.json(validated),
        installationId,
        actorId);

    return find(installationId, actorId).orElseThrow();
  }

  /**
   * Moves an installation's pin to the newest published version.
   *
   * <p>Explicit by construction: nothing else in this service writes {@code pinned_version} after
   * install, because silently altering a professional's output is how the product loses them
   * (ADR-034 §5).
   *
   * @param installationId the installation
   * @param actorId the opaque billable subject
   * @return the installation as stored
   * @throws InstallationNotFoundException when the id names nothing this actor owns
   */
  @Transactional
  public InstalledStudio upgrade(UUID installationId, String actorId) {
    InstalledStudio installation =
        find(installationId, actorId)
            .orElseThrow(() -> new InstallationNotFoundException(installationId));

    BlueprintVersion latest =
        catalogService
            .latestPublished(installation.studioKey())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "studio has no published version: " + installation.studioKey()));

    jdbcTemplate.update(
        "UPDATE studio_installation SET pinned_version = ?, status = ?"
            + " WHERE id = ? AND actor_id = ?",
        latest.version(),
        InstallationStatus.ACTIVE.name(),
        installationId,
        actorId);

    return find(installationId, actorId).orElseThrow();
  }

  /**
   * Uninstalls, keeping the configuration for a future reinstall.
   *
   * @param installationId the installation
   * @param actorId the opaque billable subject
   * @return whether a row was revoked
   */
  @Transactional
  public boolean uninstall(UUID installationId, String actorId) {
    return jdbcTemplate.update(
            "UPDATE studio_installation SET status = ? WHERE id = ? AND actor_id = ? AND status <> ?",
            InstallationStatus.REVOKED.name(),
            installationId,
            actorId,
            InstallationStatus.REVOKED.name())
        > 0;
  }

  private Optional<InstalledStudio> findByStudioKey(String studioKey, String actorId) {
    return jdbcTemplate
        .query(
            SELECT_INSTALLED + " AND i.studio_key = ?",
            (rs, rowNum) -> readInstalled(rs),
            actorId,
            studioKey)
        .stream()
        .findFirst();
  }

  /**
   * Rejects configuration keys the pinned version does not declare.
   *
   * <p>The anti-corruption boundary for install-time input (ERR-127): an unknown key is a typo or
   * an injection attempt, and letting it through would silently do nothing at run time — the
   * failure mode that costs an afternoon to diagnose. Full JSON Schema type checking of
   * <i>values</i> lands with the run-input validator, which is the surface that actually spends
   * credits.
   */
  private Map<String, String> validateConfig(
      String studioKey, String version, Map<String, String> config) {
    if (config == null || config.isEmpty()) {
      return Map.of();
    }
    JsonNode schema =
        catalogService
            .configSchema(studioKey, version)
            .map(objectMapper::readTree)
            .orElseThrow(
                () ->
                    new IllegalStateException("no config schema for " + studioKey + " " + version));

    List<String> unknown =
        config.keySet().stream().filter(key -> !schema.has(key)).sorted().toList();
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "unknown configuration keys for " + studioKey + " " + version + ": " + unknown);
    }
    return Map.copyOf(config);
  }

  private InstalledStudio readInstalled(ResultSet rs) throws SQLException {
    return new InstalledStudio(
        rs.getObject("id", UUID.class),
        rs.getString("studio_key"),
        rs.getString("label"),
        rs.getString("icon_key"),
        rs.getString("pinned_version"),
        rs.getString("latest_version"),
        InstallationStatus.valueOf(rs.getString("status")),
        columns.stringMap(rs.getString("config")),
        columns.instantAt(rs, "installed_at"),
        columns.instantAt(rs, "last_run_at"));
  }
}
