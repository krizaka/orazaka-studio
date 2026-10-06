package com.orazaka.studioservice.application.service;

import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studio.domain.exception.BlueprintValidationException;
import com.orazaka.studio.domain.model.Blueprint;
import com.orazaka.studio.domain.model.BlueprintStatus;
import com.orazaka.studio.domain.model.BlueprintStep;
import com.orazaka.studio.domain.model.StepKind;
import com.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.orazaka.studioservice.domain.port.BlueprintRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoring: draft a blueprint version, validate it, publish it.
 *
 * <p>This is what makes "a new profession ships with zero code" true (ADR-034). An admin writes
 * JSON; the DSL's own record constructors reject anything the interpreter could not run, so a
 * Studio either goes live correct or does not go live.
 *
 * <p>Publishing is one-way for content: the {@code trg_studio_blueprint_immutable} trigger refuses
 * any later edit to a published definition. Editing means minting a version, because an
 * installation pins one and a changed prompt is a changed product.
 */
@Service
public class BlueprintPublishService {

  private final JdbcTemplate jdbcTemplate;
  private final BlueprintRepository blueprintRepository;
  private final StudioCatalogService catalogService;
  private final CapabilityRoutingClient capabilityRoutingClient;

  public BlueprintPublishService(
      JdbcTemplate jdbcTemplate,
      BlueprintRepository blueprintRepository,
      StudioCatalogService catalogService,
      CapabilityRoutingClient capabilityRoutingClient) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.blueprintRepository = Objects.requireNonNull(blueprintRepository, "repository required");
    this.catalogService = Objects.requireNonNull(catalogService, "catalogue required");
    this.capabilityRoutingClient =
        Objects.requireNonNull(capabilityRoutingClient, "routing client required");
  }

  /**
   * Creates or replaces a draft version.
   *
   * <p>The definition is parsed back immediately: writing an unparseable draft would defer the
   * error to publish time, by which point the author has moved on.
   *
   * @param studioKey the Studio
   * @param version the semver to write
   * @param definition the steps-and-outputs JSON
   * @param inputSchema the run-form JSON Schema
   * @param configSchema the install-dialog JSON Schema
   * @param estimatedCredits what one run should hold
   * @param changelog what changed
   * @param authorId the admin's actor id
   * @throws StudioNotFoundException when the Studio does not exist
   * @throws BlueprintValidationException when the graph cannot be statically validated
   */
  @Transactional
  public void saveDraft(
      String studioKey,
      String version,
      String definition,
      String inputSchema,
      String configSchema,
      long estimatedCredits,
      String changelog,
      String authorId) {
    if (catalogService.find(studioKey, "fr").isEmpty()) {
      throw new StudioNotFoundException(studioKey);
    }
    jdbcTemplate.update(
        "INSERT INTO studio_blueprint (studio_key, version, status, definition, input_schema,"
            + " config_schema, estimated_credits, changelog, created_by)"
            + " VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)"
            + " ON CONFLICT (studio_key, version) DO UPDATE SET definition = EXCLUDED.definition,"
            + " input_schema = EXCLUDED.input_schema, config_schema = EXCLUDED.config_schema,"
            + " estimated_credits = EXCLUDED.estimated_credits, changelog = EXCLUDED.changelog",
        studioKey,
        version,
        BlueprintStatus.DRAFT.name(),
        definition,
        inputSchema,
        configSchema == null || configSchema.isBlank() ? "{}" : configSchema,
        estimatedCredits,
        changelog,
        authorId);

    // Parse it back through the same path the run uses: a draft that cannot be loaded is a draft
    // that cannot be published, and the author should learn that now.
    blueprintRepository
        .find(studioKey, version)
        .orElseThrow(() -> new BlueprintValidationException("draft could not be re-read"));
  }

  /**
   * Publishes a draft, minting it as the Studio's latest version.
   *
   * <p>Re-parsed first: publishing a version the interpreter cannot load would strand every
   * installation that pins it.
   *
   * <p>Then every {@code CAPABILITY} step must resolve to an <b>enabled</b> route (ADR-037 §4.3).
   * This is what makes the pack seam safe: a blueprint naming a capability whose worker was never
   * deployed fails here, in the console, in front of the admin who can fix it — instead of at 2
   * a.m. inside a saga, as a run that took the actor's credits and produced nothing.
   *
   * <p>The check reads the registry through {@code CapabilityRoutingClient}, an HTTP port onto the
   * job service, and not through its database. The reason this was previously left to a build-time
   * seed rule stands unchanged — a cross-context table read is what SEAM-001/002 forbid — but that
   * rule can only see capabilities that ship in this repository's seeds, which is precisely the set
   * a pack is not in.
   *
   * @param studioKey the Studio
   * @param version the version to publish
   * @throws BlueprintValidationException when the version does not exist, cannot be parsed, or
   *     declares a capability that resolves to no enabled route
   */
  @Transactional
  public void publish(String studioKey, String version) {
    Blueprint blueprint =
        blueprintRepository
            .find(studioKey, version)
            .orElseThrow(() -> new BlueprintValidationException("no such version: " + version));
    assertEveryCapabilityIsRoutable(blueprint);

    jdbcTemplate.update(
        "UPDATE studio_blueprint SET status = ?, published_at = now()"
            + " WHERE studio_key = ? AND version = ? AND status = ?",
        BlueprintStatus.PUBLISHED.name(),
        studioKey,
        version,
        BlueprintStatus.DRAFT.name());

    // The catalogue's latest_version is what a fresh install pins and what raises the upgrade
    // banner on existing ones, so publishing is not complete until it moves.
    jdbcTemplate.update(
        "UPDATE studio SET latest_version = ?, status = ?, updated_at = now() WHERE studio_key = ?",
        version,
        com.orazaka.studio.domain.model.StudioStatus.PUBLISHED.name(),
        studioKey);
  }

  /**
   * Refuses a blueprint whose capabilities have nowhere to run.
   *
   * <p>Names the step id <b>and</b> the capability, and reports every unroutable step rather than
   * the first: an author fixing a five-step blueprint one publish attempt at a time is a worse
   * experience than the error it replaced.
   */
  private void assertEveryCapabilityIsRoutable(Blueprint blueprint) {
    List<String> unroutable = new ArrayList<>();
    for (BlueprintStep step : blueprint.steps()) {
      if (step.kind() != StepKind.CAPABILITY) {
        continue;
      }
      if (capabilityRoutingClient.route(step.featureKey()).isEmpty()) {
        unroutable.add("step '" + step.id() + "' -> capability '" + step.featureKey() + "'");
      }
    }
    if (!unroutable.isEmpty()) {
      throw new BlueprintValidationException(
          "cannot publish "
              + blueprint.studioKey()
              + " "
              + blueprint.version()
              + ": no enabled capability route for "
              + String.join(", ", unroutable));
    }
  }

  /**
   * Deprecates a version without deleting it.
   *
   * @param studioKey the Studio
   * @param version the version to deprecate
   * @return whether a row changed
   */
  @Transactional
  public boolean deprecate(String studioKey, String version) {
    // Never DELETE: runs reference the version they executed, and deleting it would destroy their
    // reproducibility (ADR-034 §4.6).
    return jdbcTemplate.update(
            "UPDATE studio_blueprint SET status = ? WHERE studio_key = ? AND version = ?",
            BlueprintStatus.DEPRECATED.name(),
            studioKey,
            version)
        > 0;
  }
}
