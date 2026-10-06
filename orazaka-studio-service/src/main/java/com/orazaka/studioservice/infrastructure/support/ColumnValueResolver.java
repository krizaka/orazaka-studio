package com.orazaka.studioservice.infrastructure.support;

import com.orazaka.billing.domain.model.ConsumptionReport;
import com.orazaka.studio.domain.model.PackScopeGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads and writes the two column shapes every Studio row mapper meets: a nullable {@code
 * timestamptz} and a {@code jsonb} object.
 *
 * <p>These conversions were copied into four services, which is how they drifted: the same {@code
 * jsonb} column was read three subtly different ways. Holding them once makes "how a Studio row
 * becomes a domain value" a single answerable question, and keeps {@code ObjectMapper} out of the
 * services, which have no other reason to know a JSON library exists.
 *
 * <p>An injected {@link ObjectMapper} is why this is a bean and not a static utility: [ERR-127]
 * bans statics that take beans as parameters, and the mapper carries the application's
 * configuration, so re-creating one per call site would be a quiet way to diverge from it.
 */
@Component
public class ColumnValueResolver {

  private static final Logger logger = LoggerFactory.getLogger(ColumnValueResolver.class);

  /** A report that measured nothing — every quantity absent, so it prices to nothing. */
  private static final ConsumptionReport EMPTY_CONSUMPTION =
      new ConsumptionReport(null, null, null, null, null, null, null, null, null, null, null);

  private final ObjectMapper objectMapper;

  public ColumnValueResolver(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Reads a nullable timestamp column.
   *
   * @param rs the row being mapped
   * @param column the column name
   * @return the instant, or {@code null} where the column is — an unfinished run has no finish
   *     time, and that absence is meaningful rather than an error
   * @throws SQLException if the column is not in the result set
   */
  public Instant instantAt(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  /**
   * Reads a {@code jsonb} object of flat string values — configuration, chiefly.
   *
   * @param json the raw column value, possibly null
   * @return an immutable map, empty where the column is null or blank
   */
  public Map<String, String> stringMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    Map<String, String> values = new LinkedHashMap<>();
    JsonNode node = objectMapper.readTree(json);
    node.propertyStream().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
    return Map.copyOf(values);
  }

  /**
   * Reads a {@code jsonb} object preserving the JSON types.
   *
   * <p>Distinct from {@link #stringMap} on purpose: run inputs and step outputs hold lists (the
   * photos of a fan-out, the frames it produced), and flattening those to strings would lose the
   * structure the next step iterates over.
   *
   * @param json the raw column value, possibly null
   * @return a mutable-safe copy, empty where the column is null or blank
   */
  public Map<String, Object> objectMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return objectMapper.readValue(json, new TypeReference<>() {});
  }

  /**
   * Serialises a map for a {@code jsonb} column.
   *
   * @param value the map, possibly null
   * @return its JSON form; {@code {}} rather than {@code null}, so the column is always a readable
   *     object and no reader has to special-case SQL NULL
   */
  /**
   * Reads a stored measurement blob back into the billing contract's report.
   *
   * <p>Deserialised through the same {@link ObjectMapper} that wrote it, into {@link
   * ConsumptionReport} rather than a map, so the tolerance is the record's: an executor that
   * reported a field this platform does not know about is ignored, and one that reported nothing
   * yields a report whose every quantity is absent. Both settle nothing, which is the honest
   * outcome — an unmeasured step must not be billed at a guess (ADR-041).
   *
   * @param json the stored {@code consumption} column, or {@code null}
   * @return the report, never null
   */
  public ConsumptionReport consumptionReport(String json) {
    if (json == null || json.isBlank()) {
      return EMPTY_CONSUMPTION;
    }
    try {
      return objectMapper.readValue(json, ConsumptionReport.class);
    } catch (RuntimeException unreadable) {
      // A measurement we cannot parse is a measurement we must not invent. Warn and contribute
      // nothing rather than fail the run's settlement over one malformed row.
      logger.warn(
          "Unreadable consumption blob; the step contributes nothing to the run", unreadable);
      return EMPTY_CONSUMPTION;
    }
  }

  /**
   * Reads a {@code pack.scope_guard} column back into the declaration it holds.
   *
   * @param json the JSONB text, or null when the pack declares no domain
   * @return the guard, or null — the caller decides what an absent declaration means
   */
  public PackScopeGuard scopeGuard(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(json);
      List<String> terms = new ArrayList<>();
      node.path("refusedTerms").forEach(term -> terms.add(term.asString()));
      String refusal = node.path("refusal").asString();
      return terms.isEmpty() || refusal.isBlank() ? null : new PackScopeGuard(terms, refusal);
    } catch (RuntimeException unreadable) {
      // A guard that cannot be parsed is not a guard, and pretending otherwise would let a
      // SENSITIVE pack run unguarded on a corrupt row. Null means "no guard", and the caller —
      // the dispatcher — then sends no declaration, so the interceptor refuses nothing and the
      // run proceeds under the OTHER three controls. Loud, because that is a data defect.
      logger.error(
          "Unreadable scope_guard on a pack row; the turn will not be guarded", unreadable);
      return null;
    }
  }

  public String json(Map<String, ?> value) {
    return objectMapper.writeValueAsString(value == null ? Map.of() : value);
  }
}
