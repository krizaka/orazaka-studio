package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studio.domain.exception.TurnConsentRequiredException;
import com.krizaka.orazaka.studio.domain.model.CrisisResource;
import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import com.krizaka.orazaka.studio.domain.model.PackScopeGuard;
import com.krizaka.orazaka.studio.domain.model.RegulatoryClass;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The sole author of one invariant: <b>a dispatched step carries what its pack declared, and the
 * engine never supplies a subject of its own.</b>
 *
 * <p>Four declarations, added over three phases and all ferried by the same method that advances
 * the DAG: the domain a pack refuses (ADR-051), what it treats as crisis content and the reviewed
 * answer for the installation's region (ADR-055), and what the user actually wrote as opposed to
 * the template it was pasted into (ADR-055 §7).
 *
 * <p>None of these is a saga concern. They ended up in {@code dispatch()} for the same reason
 * {@code run.holdId()} did — it was the method holding the run when the payload was being built —
 * and that is the pattern ADR-041 cost 480 credits to learn. Separated before it costs a second
 * time (ADR-059).
 *
 * <p>A conversational turn (ADR-076) has no step, and reads the same declarations — and the pack's
 * system prompt — through {@link #forTurn}, from this same author.
 *
 * <p><b>Read per dispatch, never cached.</b> A pack is re-installed to change what it refuses or
 * what it answers, and a cache would keep enforcing the previous boundary for as long as it lived.
 * The cost is indexed lookups on a path already writing a row and publishing a message.
 */
@Service
public class StepDeclarationService {

  private final JdbcTemplate jdbcTemplate;
  private final ColumnValueResolver columns;

  /**
   * @param jdbcTemplate reads the pack row and the installation
   * @param columns parses the declarations the pack row holds as JSON
   */
  public StepDeclarationService(JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate required");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver required");
  }

  /**
   * The domain this Studio's pack refuses, or empty when it declares none.
   *
   * @param studioKey the Studio being dispatched
   * @return the declared guard
   */
  public Optional<PackScopeGuard> scopeGuard(String studioKey) {
    return jdbcTemplate
        .query(
            "SELECT p.scope_guard FROM pack_studio ps JOIN pack p ON p.pack_key = ps.pack_key"
                + " WHERE ps.studio_key = ? AND p.scope_guard IS NOT NULL",
            (rs, rowNum) -> columns.scopeGuard(rs.getString(1)),
            studioKey)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * What this Studio's pack treats as crisis content, comma-separated.
   *
   * @param studioKey the Studio
   * @return the declared terms, or empty
   */
  public Optional<String> crisisTerms(String studioKey) {
    return safety(studioKey)
        .map(safety -> safety.get("crisisTerms"))
        .map(
            terms ->
                terms instanceof List<?> list
                    ? list.stream().map(String::valueOf).collect(Collectors.joining(","))
                    : String.valueOf(terms))
        .filter(terms -> !terms.isBlank());
  }

  /**
   * The reviewed crisis reply for this installation's region, assembled here and never downstream.
   *
   * <p>The region comes from the installation, because that is where consent recorded it, and the
   * resource comes from the pack row with its source and verification date. A run in a region the
   * pack never sourced gets no response — but it also never installed, so this is the belt to the
   * installer's braces (ADR-055 §5).
   *
   * @param studioKey the Studio
   * @param installationId the installation, which carries the region
   * @return the full reply, or empty
   */
  public Optional<String> crisisResponse(String studioKey, UUID installationId) {
    Optional<Map<String, Object>> safety = safety(studioKey);
    if (safety.isEmpty()) {
      return Optional.empty();
    }
    String region =
        jdbcTemplate
            .query(
                "SELECT region FROM studio_installation WHERE id = ?",
                (rs, rowNum) -> rs.getString(1),
                installationId)
            .stream()
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    if (region == null) {
      return Optional.empty();
    }
    Object resources = safety.get().get("resources");
    if (!(resources instanceof Map<?, ?> byRegion)
        || !(byRegion.get(region) instanceof Map<?, ?> resource)) {
      return Optional.empty();
    }
    String line =
        resource.get("label")
            + " — "
            + resource.get("contact")
            + " ("
            + resource.get("availability")
            + ")";
    return Optional.of(safety.get().get("response") + "\n\n" + line);
  }

  /**
   * What the user actually typed, as opposed to the template it was pasted into.
   *
   * <p>A guard matching the step's resolved inputs matches the blueprint's own instructions, and a
   * wellbeing template saying <i>"tu ne poses aucun diagnostic"</i> made its pack refuse every
   * legitimate entry it received. Only the producer can tell the two apart (ADR-055 §7).
   *
   * @param runInputs the run's own inputs
   * @return the subject the guards judge, or empty
   */
  public Optional<String> guardSubject(Map<String, Object> runInputs) {
    if (runInputs == null || runInputs.isEmpty()) {
      return Optional.empty();
    }
    String joined =
        runInputs.values().stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .collect(Collectors.joining(" "));
    return joined.isBlank() ? Optional.empty() : Optional.of(joined);
  }

  /** The pack's crisis declaration as a map, or empty when it declares none. */
  private Optional<Map<String, Object>> safety(String studioKey) {
    return jdbcTemplate
        .query(
            "SELECT p.safety FROM pack_studio ps JOIN pack p ON p.pack_key = ps.pack_key"
                + " WHERE ps.studio_key = ? AND p.safety IS NOT NULL",
            (rs, rowNum) -> columns.objectMap(rs.getString(1)),
            studioKey)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * What a <b>conversational</b> turn taken under an installation must carry (ADR-076): the same
   * declarations a dispatched step carries, plus the pack's system prompt — read here, by the one
   * author of a pack's declarations [SAGA-002], so a turn and a step can never disagree about what
   * the pack refuses or answers.
   *
   * <p><b>Consent is re-checked at the turn</b>, not only at install: an installation whose
   * recorded version is not the one in force gets no declarations, and so no turn — the turn is the
   * moment the processing happens (ADR-055 §3).
   *
   * @param installationId the installation
   * @param actorId the person taking the turn
   * @return the declarations, or empty when the id names no active installation of this actor
   * @throws TurnConsentRequiredException when the recorded consent is not to the statement in force
   */
  public Optional<TurnDeclarations> forTurn(UUID installationId, String actorId) {
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
    List<String> terms = turnCrisisTerms(safety);
    CrisisResource resource = terms.isEmpty() ? null : turnResourceFor(safety, row.region());
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

  private static List<String> turnCrisisTerms(Map<String, Object> safety) {
    return safety.get("crisisTerms") instanceof List<?> list
        ? list.stream().map(String::valueOf).filter(term -> !term.isBlank()).toList()
        : List.of();
  }

  private static CrisisResource turnResourceFor(Map<String, Object> safety, String region) {
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
