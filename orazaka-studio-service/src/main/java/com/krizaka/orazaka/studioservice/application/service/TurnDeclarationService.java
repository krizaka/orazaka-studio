package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studio.domain.exception.TurnConsentRequiredException;
import com.krizaka.orazaka.studio.domain.model.CrisisResource;
import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import com.krizaka.orazaka.studio.domain.model.RegulatoryClass;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What a conversational turn taken under an installation must carry (ADR-076).
 *
 * <p>The conversational sibling of {@link StepDeclarationService}: the same pack row, the same
 * region from the same installation, the same reviewed reply — read for a turn that has no step to
 * ferry it. Read per turn, never cached, for the reason that class gives.
 *
 * <p><b>Consent is re-checked here, not only at install.</b> Bumping a pack's consent version is
 * meant to stop every existing installation until its owner agrees again (ADR-055 §3); an
 * installation whose recorded version is no longer the one in force gets no declarations, and so no
 * turn. A turn is the moment the processing happens, which is when the lawful basis has to hold.
 */
@Service
public class TurnDeclarationService {

  private final JdbcTemplate jdbcTemplate;
  private final ColumnValueResolver columns;

  /**
   * @param jdbcTemplate reads the installation and its pack row
   * @param columns parses the declarations the pack row holds as JSON
   */
  public TurnDeclarationService(JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate required");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver required");
  }

  /**
   * The declarations of one installation, scoped to its owner.
   *
   * @param installationId the installation
   * @param actorId the person taking the turn
   * @return the declarations, or empty when the id names no active installation of this actor
   * @throws TurnConsentRequiredException when the recorded consent is not to the statement in force
   */
  public Optional<TurnDeclarations> forInstallation(UUID installationId, String actorId) {
    return jdbcTemplate
        .query(
            """
            SELECT i.studio_key, i.region, i.consent_version AS recorded_consent,
                   p.pack_key, p.regulatory_class, p.consent_version, p.consent_statement,
                   p.scope_guard, p.safety
              FROM studio_installation i
              JOIN pack_studio ps ON ps.studio_key = i.studio_key
              JOIN pack p ON p.pack_key = ps.pack_key
             WHERE i.id = ? AND i.actor_id = ? AND i.status = ?
            """,
            (rs, rowNum) ->
                new InstallationRow(
                    rs.getString("studio_key"),
                    rs.getString("region"),
                    rs.getString("recorded_consent"),
                    rs.getString("pack_key"),
                    rs.getString("regulatory_class"),
                    rs.getString("consent_version"),
                    rs.getString("consent_statement"),
                    rs.getString("scope_guard"),
                    rs.getString("safety")),
            installationId,
            actorId,
            InstallationStatus.ACTIVE.name())
        .stream()
        .findFirst()
        .map(row -> declarationsOf(installationId, row));
  }

  private TurnDeclarations declarationsOf(UUID installationId, InstallationRow row) {
    if (RegulatoryClass.REGULATED.name().equals(row.regulatoryClass())
        && !Objects.equals(row.consentVersion(), row.recordedConsent())) {
      throw new TurnConsentRequiredException(row.consentVersion(), row.consentStatement());
    }
    Map<String, Object> safety = columns.objectMap(row.safety());
    List<String> terms = crisisTerms(safety);
    CrisisResource resource = terms.isEmpty() ? null : resourceFor(safety, row.region());
    if (!terms.isEmpty() && resource == null) {
      // A region the pack never sourced has no reply to give, and the installer refuses to install
      // there — so this is a row that changed under the installation. No line, no turn: a crisis
      // guard answering with nothing is the failure ADR-055 §5 exists to prevent.
      throw new IllegalStateException(
          "pack " + row.packKey() + " has no verified crisis line for this installation's region");
    }
    Object systemPrompt = safety.get("systemPrompt");
    return new TurnDeclarations(
        installationId,
        row.studioKey(),
        row.packKey(),
        row.region(),
        columns.scopeGuard(row.scopeGuard()),
        terms,
        terms.isEmpty() ? null : String.valueOf(safety.get("response")),
        resource,
        systemPrompt == null ? null : String.valueOf(systemPrompt));
  }

  private static List<String> crisisTerms(Map<String, Object> safety) {
    return safety.get("crisisTerms") instanceof List<?> list
        ? list.stream().map(String::valueOf).filter(term -> !term.isBlank()).toList()
        : List.of();
  }

  private static CrisisResource resourceFor(Map<String, Object> safety, String region) {
    if (region == null
        || !(safety.get("resources") instanceof Map<?, ?> byRegion)
        || !(byRegion.get(region) instanceof Map<?, ?> resource)) {
      return null;
    }
    return new CrisisResource(
        String.valueOf(resource.get("region")),
        String.valueOf(resource.get("label")),
        String.valueOf(resource.get("contact")),
        String.valueOf(resource.get("availability")),
        String.valueOf(resource.get("sourceUrl")),
        LocalDate.parse(String.valueOf(resource.get("verifiedOn"))));
  }

  /** One installation joined to its pack row. */
  private record InstallationRow(
      String studioKey,
      String region,
      String recordedConsent,
      String packKey,
      String regulatoryClass,
      String consentVersion,
      String consentStatement,
      String scopeGuard,
      String safety) {}
}
