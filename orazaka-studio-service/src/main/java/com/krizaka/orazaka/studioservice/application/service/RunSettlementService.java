package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.MeteredStep;
import com.krizaka.orazaka.jobs.domain.model.CapabilityRoute;
import com.krizaka.orazaka.jobs.domain.model.FailureCause;
import com.krizaka.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintStep;
import com.krizaka.orazaka.studio.domain.model.RunStepStatus;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The sole author of one invariant: <b>a run's credit hold is closed exactly once</b> — settled at
 * the sum of what its steps measured, or released, and never both.
 *
 * <p>It exists because that invariant had three authors and the concentration had already cost.
 * {@code RunSagaService} carried it alongside DAG advancement, and leaked one into the other: it
 * passed {@code run.holdId()} into every {@code StepDispatch}, because the method building a step's
 * payload had the run's billing identity in scope and used it. The first step to finish then
 * settled the whole run at the hold's own rate — 480 credits reserved, 5 debited — and every later
 * step found the hold closed (ADR-041). That is the failure concentration produces: not a class
 * that is too long, but a method that reaches for a fact belonging to a different rule because it
 * happens to be holding it.
 *
 * <p>The other two authors were {@code StudioRunService}, releasing on cancel, and {@code
 * RunSweeper}, releasing on sweep. Both now call this class, so there is one place to read to know
 * when a run stops costing money (ADR-059).
 *
 * <p><b>Deliberately not moved here</b>: opening the hold. A run's estimate is taken at creation,
 * where the blueprint and the actor are, and moving it would put a second concern in this class to
 * satisfy a symmetry nobody needs. This class answers "when does it close", which is the question
 * that had three answers.
 */
@Service
public class RunSettlementService {

  private static final Logger logger = LoggerFactory.getLogger(RunSettlementService.class);

  /** A producer that named no model; the pricebook falls back to the capability's own row. */
  private static final String UNRESOLVED_MODEL = "default";

  private final CreditReservationService creditReservationService;
  private final CapabilityRoutingClient capabilityRoutingClient;
  private final JdbcTemplate jdbcTemplate;
  private final ColumnValueResolver columns;

  /**
   * @param creditReservationService the ledger's client
   * @param capabilityRoutingClient resolves each step's billable capability
   * @param jdbcTemplate reads what the steps measured
   * @param columns parses a step's recorded consumption
   */
  public RunSettlementService(
      CreditReservationService creditReservationService,
      CapabilityRoutingClient capabilityRoutingClient,
      JdbcTemplate jdbcTemplate,
      ColumnValueResolver columns) {
    this.creditReservationService =
        Objects.requireNonNull(creditReservationService, "CreditReservationService required");
    this.capabilityRoutingClient =
        Objects.requireNonNull(capabilityRoutingClient, "CapabilityRoutingClient required");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate required");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver required");
  }

  /**
   * Closes a successful run's hold at the sum of what its steps measured.
   *
   * @param runId the run
   * @param holdId its reservation
   * @param blueprint the version it pinned, for each step's capability
   */
  public void settleSucceeded(UUID runId, String holdId, Blueprint blueprint) {
    creditReservationService.settle(holdId, runId, measured(runId, blueprint));
  }

  /**
   * Closes a failed run's hold according to the <b>declared</b> cause (ADR-053).
   *
   * <p>We bill for work we performed correctly and never for our own failure: a gate that declined
   * and a payload an executor rejected both settle what ran; a defect, an absent dependency and a
   * missed deadline release the whole hold.
   *
   * @param runId the run
   * @param holdId its reservation
   * @param blueprint the version it pinned
   * @param cause why it failed; {@code null} reads as {@link FailureCause#EXECUTOR_FAULT}
   * @param reason the message recorded against the release
   * @return whether the run had consumed real compute, for the caller's metric
   */
  public boolean settleFailed(
      UUID runId, String holdId, Blueprint blueprint, FailureCause cause, String reason) {
    FailureCause declared = cause == null ? FailureCause.EXECUTOR_FAULT : cause;
    List<MeteredStep> steps = measured(runId, blueprint);
    boolean consumed = !steps.isEmpty();
    if (declared.settlesMeasuredWork() && consumed) {
      creditReservationService.settle(holdId, runId, steps);
    } else {
      creditReservationService.release(holdId, reason);
    }
    return consumed;
  }

  /**
   * Closes a hold with nothing billed — a cancelled run, or one the sweeper gave up on.
   *
   * @param holdId the reservation
   * @param reason what is recorded against it
   */
  public void releaseAbandoned(String holdId, String reason) {
    creditReservationService.release(holdId, reason);
  }

  /**
   * What the run's steps measured, paired with the pricebook key that prices each.
   *
   * <p>Read from {@code studio_run_step} rather than from an accumulator: the rows are what survive
   * a restart between one step finishing and the next starting, and a run whose measurements are
   * lost settles at nothing (ADR-041).
   *
   * <p>The capability comes from the registry, never from the blueprint: {@code
   * billable_capability} is a column of a table the job context owns. A step whose route no longer
   * resolves is skipped with a warning — it cannot be priced, and refusing to settle the whole run
   * because one capability was withdrawn mid-flight would strand the actor's credits.
   */
  private List<MeteredStep> measured(UUID runId, Blueprint blueprint) {
    Map<String, String> capabilityByStep = new LinkedHashMap<>();
    for (BlueprintStep step : blueprint.steps()) {
      if (step.featureKey() != null) {
        capabilityByStep.put(step.id(), step.featureKey());
      }
    }
    List<MeteredStep> metered = new ArrayList<>();
    jdbcTemplate
        .query(
            "SELECT step_id, model_name, consumption FROM studio_run_step"
                + " WHERE run_id = ? AND consumption IS NOT NULL AND status = ?",
            (rs, rowNum) ->
                new MeasuredRow(
                    rs.getString("step_id"),
                    rs.getString("model_name"),
                    rs.getString("consumption")),
            runId,
            RunStepStatus.SUCCEEDED.name())
        .forEach(
            row -> {
              String featureKey = capabilityByStep.get(row.stepId());
              if (featureKey == null) {
                return; // a connector step: it consumes no metered compute
              }
              Optional<String> capability =
                  capabilityRoutingClient
                      .route(featureKey)
                      .map(CapabilityRoute::billableCapability)
                      .filter(name -> name != null && !name.isBlank());
              if (capability.isEmpty()) {
                logger.warn(
                    "Run {} step {} ({}) has no billable capability; it contributes nothing to the"
                        + " run's cost",
                    runId,
                    row.stepId(),
                    featureKey);
                return;
              }
              metered.add(
                  new MeteredStep(
                      BillableCapability.valueOf(capability.get()),
                      UNRESOLVED_MODEL.equals(row.modelName()) ? null : row.modelName(),
                      columns.consumptionReport(row.consumption())));
            });
    return metered;
  }

  /** One step's measurement, as the row holds it. */
  private record MeasuredRow(String stepId, String modelName, String consumption) {}
}
