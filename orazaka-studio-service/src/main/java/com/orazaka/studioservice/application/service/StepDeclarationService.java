package com.orazaka.studioservice.application.service;

import com.orazaka.studio.domain.model.PackScopeGuard;
import com.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
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
}
