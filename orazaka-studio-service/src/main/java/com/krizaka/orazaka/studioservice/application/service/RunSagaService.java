package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.messaging.dedup.MessageDedup;
import com.krizaka.orazaka.jobs.domain.exception.UnroutableCapabilityException;
import com.krizaka.orazaka.jobs.domain.model.CapabilityRoute;
import com.krizaka.orazaka.jobs.domain.model.DataClass;
import com.krizaka.orazaka.jobs.domain.model.FailureCause;
import com.krizaka.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintStep;
import com.krizaka.orazaka.studio.domain.model.ErrorPolicy;
import com.krizaka.orazaka.studio.domain.model.RunScope;
import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studio.domain.model.RunStepStatus;
import com.krizaka.orazaka.studio.domain.model.StepDispatch;
import com.krizaka.orazaka.studio.domain.model.StepKind;
import com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository;
import com.krizaka.orazaka.studioservice.domain.port.StepExecutionClient;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The DAG interpreter: it decides which steps are ready and closes the run when none are left.
 *
 * <p>Every advance is driven by a job outcome, so the engine is a state machine over {@code
 * studio_run_step} rather than a thread that waits. That is what lets a run last hours without
 * holding anything open, and what makes a restart mid-run harmless.
 *
 * <p>Two idempotency guards, because an at-least-once redelivery must not re-submit a paid job: the
 * conditional {@code WHERE status = 'RUNNING'} below, and {@link MessageDedup} above it.
 */
@Service
public class RunSagaService {

  private static final Logger logger = LoggerFactory.getLogger(RunSagaService.class);

  /** Hard ceiling on {@code forEach} expansion. On owned hardware the accelerator is the budget. */
  private static final String FAN_OUT_MAX_KEY = "run.fan-out-max";

  private static final int FAN_OUT_MAX_FALLBACK = 10;

  /**
   * What a producer sends when it chose no model. Mirrored from {@code JobCommand}: it is a
   * sentinel, not a model name, and passing it to the pricebook looks for a row called "default"
   * instead of falling back to the capability's own.
   */
  private static final String UNRESOLVED_MODEL = "default";

  private final JdbcTemplate jdbcTemplate;
  private final BlueprintRepository blueprintRepository;
  private final StepExecutionClient stepExecutionClient;
  private final RunSettlementService settlementService;
  private final StepDeclarationService declarations;
  private final CapabilityRoutingClient capabilityRoutingClient;
  private final StudioRuntimeConfigService runtimeConfigService;
  private final OutboxService outboxService;
  private final ColumnValueResolver columns;
  private final MeterRegistry meterRegistry;
  private final RunAuditService runAuditService;

  public RunSagaService(
      JdbcTemplate jdbcTemplate,
      BlueprintRepository blueprintRepository,
      StepExecutionClient stepExecutionClient,
      CapabilityRoutingClient capabilityRoutingClient,
      RunSettlementService settlementService,
      StepDeclarationService declarations,
      StudioRuntimeConfigService runtimeConfigService,
      OutboxService outboxService,
      ColumnValueResolver columns,
      MeterRegistry meterRegistry,
      RunAuditService runAuditService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.blueprintRepository = Objects.requireNonNull(blueprintRepository, "repository required");
    this.stepExecutionClient = Objects.requireNonNull(stepExecutionClient, "client required");
    this.capabilityRoutingClient =
        Objects.requireNonNull(capabilityRoutingClient, "CapabilityRoutingClient required");
    this.settlementService =
        Objects.requireNonNull(settlementService, "RunSettlementService required");
    this.declarations = Objects.requireNonNull(declarations, "StepDeclarationService required");
    this.runtimeConfigService = Objects.requireNonNull(runtimeConfigService, "config required");
    this.outboxService = Objects.requireNonNull(outboxService, "OutboxService required");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver required");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry required");
    this.runAuditService = Objects.requireNonNull(runAuditService, "RunAuditService required");
  }

  /**
   * Applies a terminal job outcome and advances the DAG.
   *
   * @param jobId the job that finished
   * @param output what it produced, empty on failure
   * @param error the failure message, {@code null} on success
   */
  @Transactional
  public void applyOutcome(String jobId, Map<String, Object> output, String error) {
    applyOutcome(jobId, output, Map.of(), null, error, null);
  }

  /**
   * Records a step's outcome together with what it consumed.
   *
   * <p>The measurements are stored on the step row rather than accumulated in memory because the
   * run's hold is settled once, at the end, against their sum — and a restart between step four
   * finishing and step five starting would otherwise lose everything measured so far. A run whose
   * measurements are lost settles at nothing, which is the failure this whole change exists to
   * remove (ADR-041).
   *
   * @param jobId the job whose outcome this is
   * @param output what the executor produced
   * @param consumption the raw measurements, empty when the executor reported none
   * @param model the engine that ran, {@code null} when the producer named none
   * @param error the failure message, {@code null} on success
   * @param cause the executor's declared reason for failing, {@code null} on success; a failure
   *     that declares none is an {@link FailureCause#EXECUTOR_FAULT} (ADR-053)
   */
  @Transactional
  public void applyOutcome(
      String jobId,
      Map<String, Object> output,
      Map<String, Object> consumption,
      String model,
      String error,
      FailureCause cause) {
    Optional<StepRef> located = locateStep(jobId);
    if (located.isEmpty()) {
      // Not one of ours: the saga queue sees every job event on the platform, including jobs
      // submitted from chat. Ignoring them is the normal path, not an error.
      return;
    }
    StepRef ref = located.get();

    RunStepStatus outcome = error == null ? RunStepStatus.SUCCEEDED : RunStepStatus.FAILED;
    int applied =
        jdbcTemplate.update(
            "UPDATE studio_run_step SET status = ?, output = ?::jsonb, error_message = ?,"
                + " failure_cause = ?, consumption = ?::jsonb, model_name = ?, finished_at = now()"
                + " WHERE run_id = ? AND step_id = ? AND ordinal = ? AND status = ?",
            outcome.name(),
            columns.json(output),
            error,
            // Stored beside the message, not derived from it later: by the time the DAG advances,
            // the executor that knew why is long gone (ADR-053).
            error == null ? null : (cause == null ? FailureCause.EXECUTOR_FAULT : cause).name(),
            // Written in the SAME statement that marks the step terminal, and guarded by the same
            // status predicate: a redelivery that loses the race applies neither, so a step can
            // never be counted twice in the run's total.
            consumption == null || consumption.isEmpty() ? null : columns.json(consumption),
            model,
            ref.runId(),
            ref.stepId(),
            ref.ordinal(),
            RunStepStatus.RUNNING.name());

    if (applied == 0) {
      logger.debug("Outcome for job {} was already applied — redelivery, ignoring", jobId);
      return;
    }
    if (outcome == RunStepStatus.FAILED) {
      // Put the node back in flight if its policy allows. This is a side-effect, not a branch:
      // the advance below must still run, because some *other* node may already have failed
      // hard, and returning early here would leave that run hanging instead of failing it.
      resubmitted(ref);
    }
    advance(ref.runId());
  }

  /**
   * Re-submits a failed step that has attempts left under {@link ErrorPolicy#RETRY}.
   *
   * <p>Without this, {@code onError: RETRY} is a lie the blueprint tells: the policy and {@code
   * maxAttempts} would be parsed, validated and then silently treated as {@code FAIL}. A transient
   * model timeout would end a run the author explicitly said should try again.
   *
   * <p>The attempt counter lives on the row, so a retry survives a restart and cannot loop: the
   * guard is {@code attempts < maxAttempts}, evaluated against what the database recorded, not
   * against anything held in memory.
   *
   * @param ref the node that just failed
   * @return whether it was put back in flight; the row returns to {@code RUNNING}, so the caller's
   *     {@code advance} sees it in flight rather than failed and neither fails nor unblocks on it
   */
  private boolean resubmitted(StepRef ref) {
    RunRow run = loadRun(ref.runId());
    if (run == null || isTerminal(run.status())) {
      return false;
    }
    Blueprint blueprint =
        blueprintRepository.find(run.studioKey(), run.blueprintVersion()).orElse(null);
    if (blueprint == null) {
      return false;
    }
    BlueprintStep step =
        blueprint.steps().stream()
            .filter(candidate -> candidate.id().equals(ref.stepId()))
            .findFirst()
            .orElse(null);
    if (step == null || step.onError() != ErrorPolicy.RETRY) {
      return false;
    }

    Integer attempts =
        jdbcTemplate.queryForObject(
            "SELECT attempts FROM studio_run_step WHERE run_id = ? AND step_id = ? AND ordinal = ?",
            Integer.class,
            ref.runId(),
            ref.stepId(),
            ref.ordinal());
    if (attempts == null || attempts >= step.maxAttempts()) {
      return false;
    }

    RunScope scope =
        buildScope(run, blueprint).withItem(itemFor(step, run, blueprint, ref.ordinal()));
    Map<String, Object> resolved = new LinkedHashMap<>();
    // resolveValue, not resolve: a lone placeholder over a list stays a list, so a
    // composition's `photos` reaches its executor as assets rather than as one
    // newline-joined string. Prose is untouched (ADR-046).
    step.inputs().forEach((key, template) -> resolved.put(key, scope.resolveValue(template)));

    String jobId;
    try {
      jobId =
          stepExecutionClient.submit(
              new StepDispatch(
                  run.id(),
                  step.id(),
                  ref.ordinal(),
                  step.featureKey(),
                  step.connectorType(),
                  resolved,
                  run.actorId(),
                  run.correlationId(),
                  effectiveConfig(run, blueprint),
                  declarations.scopeGuard(run.studioKey()),
                  declarations.crisisTerms(run.studioKey()),
                  declarations.crisisResponse(run.studioKey(), run.installationId()),
                  declarations.guardSubject(run.inputs()),
                  run.dataClass()));
    } catch (UnroutableCapabilityException e) {
      // A retry of a step whose capability has since lost its route is not a retry, it is a run
      // that can no longer finish. Reporting "not resubmitted" lets the caller fail it as the
      // failed step it already is, rather than retrying into the same refusal until attempts run
      // out.
      logger.error("Run {} cannot re-dispatch step {}", run.id(), step.id(), e);
      return false;
    }

    jdbcTemplate.update(
        "UPDATE studio_run_step SET status = ?, job_id = ?, attempts = attempts + 1,"
            + " error_message = NULL, finished_at = NULL"
            + " WHERE run_id = ? AND step_id = ? AND ordinal = ?",
        RunStepStatus.RUNNING.name(),
        jobId,
        ref.runId(),
        ref.stepId(),
        ref.ordinal());

    logger.info(
        "Retrying step {}[{}] of run {} (attempt {} of {})",
        step.id(),
        ref.ordinal(),
        run.id(),
        attempts + 1,
        step.maxAttempts());
    return true;
  }

  /** The fan-out element a given ordinal was dispatched with, so a retry re-renders identically. */
  private Object itemFor(BlueprintStep step, RunRow run, Blueprint blueprint, int ordinal) {
    if (step.forEach() == null) {
      return null;
    }
    List<Object> items = buildScope(run, blueprint).expand(step.forEach());
    return ordinal < items.size() ? items.get(ordinal) : null;
  }

  /**
   * Re-evaluates a run: dispatches whatever became ready, or closes it when nothing is left.
   *
   * @param runId the run to advance
   */
  @Transactional
  public void advance(UUID runId) {
    RunRow run = loadRun(runId);
    if (run == null || isTerminal(run.status())) {
      return;
    }
    Blueprint blueprint =
        blueprintRepository
            .find(run.studioKey(), run.blueprintVersion())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "run " + runId + " pins a version that no longer parses"));

    Map<String, RunStepStatus> states = stepStates(runId);
    Map<String, FailureCause> causes = stepCauses(runId);

    // A required step that failed ends the run: compensate, settle or release, stop submitting.
    // The policy is resolved AGAINST THE DECLARED CAUSE, so an author can say "SKIP when the model
    // was unavailable, FAIL when a guard refused" — the distinction the validation pack had to
    // give up when both arrived identical (ADR-053 §5).
    for (BlueprintStep step : blueprint.steps()) {
      if (states.get(step.id()) != RunStepStatus.FAILED) {
        continue;
      }
      FailureCause cause = causes.getOrDefault(step.id(), FailureCause.EXECUTOR_FAULT);
      if (step.onErrorFor(cause) != ErrorPolicy.SKIP) {
        failRun(run, blueprint, stepFailureMessage(runId, step.id()), cause);
        return;
      }
    }

    RunScope scope = buildScope(run, blueprint);
    List<BlueprintStep> ready =
        blueprint.steps().stream()
            .filter(step -> states.get(step.id()) == null)
            .filter(step -> dependenciesSatisfied(step, states))
            .toList();

    for (BlueprintStep step : ready) {
      dispatch(run, blueprint, step, scope);
    }

    if (ready.isEmpty() && allTerminal(blueprint, stepStates(runId))) {
      succeedRun(run, blueprint, buildScope(run, blueprint));
    }
  }

  /** A step is ready when every dependency has reached a terminal state. */
  private static boolean dependenciesSatisfied(
      BlueprintStep step, Map<String, RunStepStatus> states) {
    return step.dependsOn().stream()
        .allMatch(
            dependency -> {
              RunStepStatus state = states.get(dependency);
              return state != null
                  && state != RunStepStatus.PENDING
                  && state != RunStepStatus.RUNNING;
            });
  }

  private void dispatch(RunRow run, Blueprint blueprint, BlueprintStep step, RunScope scope) {
    if (step.condition() != null && !scope.matches(step.condition())) {
      recordSkipped(run.id(), step.id());
      advanceLater(run.id());
      return;
    }
    if (step.kind() == StepKind.APPROVAL) {
      park(run.id(), step.id());
      return;
    }
    if (step.kind() == StepKind.TRANSFORM) {
      transform(run, step, scope);
      advanceLater(run.id());
      return;
    }
    if (step.kind() != StepKind.CAPABILITY && step.kind() != StepKind.CONNECTOR) {
      // KNOWLEDGE has no executor on this platform yet. Skipping is honest and keeps the run
      // moving; failing would strand blueprints that use it for optional grounding.
      logger.info("Step kind {} is not executable yet — skipping {}", step.kind(), step.id());
      recordSkipped(run.id(), step.id());
      advanceLater(run.id());
      return;
    }

    Map<String, String> brandContext = effectiveConfig(run, blueprint);
    List<Object> items = expand(step, scope);
    for (int ordinal = 0; ordinal < items.size(); ordinal++) {
      RunScope itemScope = scope.withItem(items.get(ordinal));
      Map<String, Object> resolved = new LinkedHashMap<>();
      step.inputs().forEach((key, template) -> resolved.put(key, itemScope.resolveValue(template)));

      String jobId;
      try {
        jobId =
            stepExecutionClient.submit(
                new StepDispatch(
                    run.id(),
                    step.id(),
                    ordinal,
                    step.featureKey(),
                    step.connectorType(),
                    resolved,
                    run.actorId(),
                    run.correlationId(),
                    brandContext,
                    declarations.scopeGuard(run.studioKey()),
                    declarations.crisisTerms(run.studioKey()),
                    declarations.crisisResponse(run.studioKey(), run.installationId()),
                    declarations.guardSubject(run.inputs()),
                    run.dataClass()));
      } catch (UnroutableCapabilityException e) {
        // Caught, not propagated: letting it out of an @Transactional method rolls the run back to
        // whatever it was and leaves it in flight for the sweeper to time out an hour later. The
        // run has to END, with the capability named, and the hold released (failRun does that).
        // Publish-time validation should have caught this; reaching here means a capability was
        // disabled after a blueprint went live, which is exactly when nobody is watching.
        logger.error("Run {} cannot dispatch step {}", run.id(), step.id(), e);
        failRun(
            run,
            blueprint,
            "step '" + step.id() + "' has no route for capability '" + e.featureKey() + "'",
            // Our own dispatch site, our own exception: a capability withdrawn from under a
            // published blueprint is a platform problem, and the actor pays nothing for it.
            FailureCause.PLATFORM_UNAVAILABLE);
        return;
      }

      jdbcTemplate.update(
          "INSERT INTO studio_run_step (run_id, step_id, ordinal, job_id, status, attempts,"
              + " latency_class, started_at) VALUES (?, ?, ?, ?, ?, 1, ?, now())"
              + " ON CONFLICT (run_id, step_id, ordinal) DO NOTHING",
          run.id(),
          step.id(),
          ordinal,
          jobId,
          RunStepStatus.RUNNING.name(),
          // Stamped here, not re-resolved by the sweeper: `started_at` on this very row is when
          // the deadline starts, so the ceiling that applies is the lane the platform held when
          // the work was sent (ADR-067).
          laneOf(step));
    }
  }

  /**
   * Executes a {@link StepKind#TRANSFORM} step in-process.
   *
   * <p>Declarative reshaping and nothing else: each declared input is resolved against the scope
   * and the resulting map becomes the step's output. That is the whole feature — pick a field out
   * of an upstream result, join two of them, relabel one for the step that follows.
   *
   * <p>There is deliberately <b>no</b> evaluation here beyond the templating grammar. A transform
   * that could compute would be an expression language by another name, and the reason the grammar
   * is not Turing-complete is that a blueprint is untrusted input even from an admin (ADR-034
   * §6.2). Anything the grammar cannot express is a new step kind, which is reviewable.
   *
   * <p>It costs nothing and touches no executor, so it never takes a job id — which is also why the
   * run detail shows it with none.
   */
  private void transform(RunRow run, BlueprintStep step, RunScope scope) {
    Map<String, Object> reshaped = new LinkedHashMap<>();
    step.inputs().forEach((key, template) -> reshaped.put(key, scope.resolve(template)));

    jdbcTemplate.update(
        "INSERT INTO studio_run_step (run_id, step_id, ordinal, status, output, attempts,"
            + " started_at, finished_at) VALUES (?, ?, 0, ?, ?::jsonb, 1, now(), now())"
            + " ON CONFLICT (run_id, step_id, ordinal) DO NOTHING",
        run.id(),
        step.id(),
        RunStepStatus.SUCCEEDED.name(),
        columns.json(reshaped));
  }

  /**
   * Expands a {@code forEach} source into its items, capped.
   *
   * <p>An uncapped fan-out over forty photos is a self-inflicted outage on a single Mac, so the cap
   * is a hard truncation rather than a rejection: producing a shorter reel beats refusing the run
   * after the actor has already paid the hold.
   */
  private List<Object> expand(BlueprintStep step, RunScope scope) {
    if (step.forEach() == null) {
      return java.util.Collections.singletonList(null);
    }
    List<Object> items = scope.expand(step.forEach());
    int cap = runtimeConfigService.intValue(FAN_OUT_MAX_KEY, FAN_OUT_MAX_FALLBACK);
    int effective = Math.min(Math.min(items.size(), cap), Math.max(step.maxParallel(), 1) * cap);
    return items.size() > effective ? items.subList(0, effective) : items;
  }

  private RunScope buildScope(RunRow run, Blueprint blueprint) {
    Map<String, Object> outputs = new LinkedHashMap<>();
    Map<String, String> outNames = new LinkedHashMap<>();
    blueprint.steps().stream()
        .filter(step -> step.out() != null)
        .forEach(step -> outNames.put(step.id(), step.out()));

    jdbcTemplate
        .query(
            "SELECT step_id, ordinal, output FROM studio_run_step"
                + " WHERE run_id = ? AND status = ? ORDER BY step_id, ordinal",
            (rs, rowNum) ->
                Map.entry(rs.getString("step_id"), columns.objectMap(rs.getString("output"))),
            run.id(),
            RunStepStatus.SUCCEEDED.name())
        .forEach(
            entry -> {
              String out = outNames.get(entry.getKey());
              if (out == null) {
                return;
              }
              // A fan-out step publishes a list; a single-shot one publishes its map directly, so
              // {{steps.x.field}} works without the author knowing which it was.
              Object existing = outputs.get(out);
              if (existing == null) {
                outputs.put(out, entry.getValue());
              } else if (existing instanceof List<?> list) {
                List<Object> grown = new ArrayList<>(list);
                grown.add(entry.getValue());
                outputs.put(out, grown);
              } else {
                outputs.put(out, new ArrayList<>(List.of(existing, entry.getValue())));
              }
            });

    return new RunScope(run.inputs(), effectiveConfig(run, blueprint), outputs, null, Map.of());
  }

  /**
   * The installation's answers layered over the blueprint's declared defaults.
   *
   * <p>Without this the defaults are parsed and discarded: an actor who left {@code tone} blank at
   * install time would have {@code {{config.tone}}} render as the empty string, silently dropping a
   * word out of the middle of a prompt. The blueprint author declared a default precisely so that
   * could not happen.
   *
   * <p>The actor always wins — their configuration is the whole point of an installation; the
   * blueprint only supplies what they did not answer.
   */
  private Map<String, String> effectiveConfig(RunRow run, Blueprint blueprint) {
    Map<String, String> effective = new LinkedHashMap<>(blueprint.configSchemaDefaults());
    effective.putAll(installationConfig(run.installationId()));
    return effective;
  }

  private Map<String, String> installationConfig(UUID installationId) {
    return jdbcTemplate
        .query(
            "SELECT config FROM studio_installation WHERE id = ?",
            (rs, rowNum) -> columns.stringMap(rs.getString("config")),
            installationId)
        .stream()
        .findFirst()
        .orElseGet(Map::of);
  }

  private void succeedRun(RunRow run, Blueprint blueprint, RunScope scope) {
    Map<String, Object> outputs = new LinkedHashMap<>();
    blueprint.outputs().forEach(output -> outputs.put(output.key(), scope.resolve(output.from())));

    jdbcTemplate.update(
        "UPDATE studio_run SET status = ?, outputs = ?::jsonb, finished_at = now()"
            + " WHERE id = ? AND finished_at IS NULL",
        RunStatus.SUCCEEDED.name(),
        columns.json(outputs),
        run.id());

    settlementService.settleSucceeded(run.id(), run.holdId(), blueprint);
    runAuditService.recordFinished(run.id(), run.actorId(), run.studioKey(), true, null);
    outboxService.append(run.id().toString(), "evt.studio.run.succeeded", runEvent(run));
  }

  /**
   * Ends a run that failed, settling or releasing according to the <b>declared</b> cause.
   *
   * <p>ADR-046 §2 rejected this policy because the boundary was undecidable: {@code job.{id}.error}
   * carried prose, and a classifier over prose would have billed a customer for our own defect. The
   * cause is now declared by the executor that failed (ADR-053), so the boundary is readable and
   * the policy applies:
   *
   * <ul>
   *   <li><b>The platform performed correctly</b> — {@code GUARD_REFUSAL}, {@code INPUT_INVALID} —
   *       and the run settles what its steps measured, never its estimate. A gate declining to
   *       serve is the platform doing its job; so is an executor rejecting a payload it checked.
   *   <li><b>The platform failed</b> — {@code EXECUTOR_FAULT}, {@code PLATFORM_UNAVAILABLE}, {@code
   *       TIMEOUT} — and the whole hold goes back, however much ran before it.
   * </ul>
   *
   * <p>A run whose cause is absent releases, because {@code FailureCause.of(null)} is {@code
   * EXECUTOR_FAULT}. That is the fail-closed direction: we do not bill and do not accuse for want
   * of information.
   */
  private void failRun(RunRow run, Blueprint blueprint, String reason, FailureCause cause) {
    FailureCause declared = cause == null ? FailureCause.EXECUTOR_FAULT : cause;

    jdbcTemplate.update(
        "UPDATE studio_run SET status = ?, error_message = ?, failure_cause = ?,"
            + " finished_at = now() WHERE id = ? AND finished_at IS NULL",
        RunStatus.FAILED.name(),
        reason,
        declared.name(),
        run.id());
    jdbcTemplate.update(
        "UPDATE studio_run_step SET status = ? WHERE run_id = ? AND status IN (?, ?)",
        RunStepStatus.CANCELLED.name(),
        run.id(),
        RunStepStatus.PENDING.name(),
        RunStepStatus.RUNNING.name());

    // Closing the hold belongs to RunSettlementService, which is the only author of it (ADR-059).
    // This method decides that the run is over; what that costs is a different rule.
    boolean consumed =
        settlementService.settleFailed(run.id(), run.holdId(), blueprint, declared, reason);

    meterRegistry
        // Named on the SAGA, not on `orazaka.studio.*`: that prefix is the pack preference
        // namespace on the wire, and [PACK-002] refuses it in engine code.
        .counter(
            "orazaka.saga.run.failed",
            "consumedCompute",
            Boolean.toString(consumed),
            "cause",
            declared.name())
        .increment();

    // The category, never the reason: `reason` is the failed step's message, which for a refusal is
    // the pack's reviewed answer — a crisis response on a REGULATED pack (ADR-065).
    runAuditService.recordFinished(run.id(), run.actorId(), run.studioKey(), false, declared);
    outboxService.append(run.id().toString(), "evt.studio.run.failed", runEvent(run));
  }

  private Map<String, Object> runEvent(RunRow run) {
    return Map.of(
        "runId", run.id().toString(),
        "actorId", run.actorId(),
        "studioKey", run.studioKey(),
        "blueprintVersion", run.blueprintVersion(),
        "correlationId", run.correlationId());
  }

  /**
   * The lane this step's capability declares, or the fail-closed default when nothing answers.
   *
   * <p>A step whose capability cannot be resolved is about to fail at dispatch anyway; giving it
   * the batch ceiling means the sweeper does not reap it before that happens.
   */
  private String laneOf(BlueprintStep step) {
    if (step.featureKey() == null) {
      return CapabilityRoute.DEFAULT_LATENCY_CLASS;
    }
    return capabilityRoutingClient
        .route(step.featureKey())
        .map(CapabilityRoute::latencyClass)
        .orElse(CapabilityRoute.DEFAULT_LATENCY_CLASS);
  }

  private void recordSkipped(UUID runId, String stepId) {
    jdbcTemplate.update(
        "INSERT INTO studio_run_step (run_id, step_id, ordinal, status, started_at, finished_at)"
            + " VALUES (?, ?, 0, ?, now(), now())"
            + " ON CONFLICT (run_id, step_id, ordinal) DO NOTHING",
        runId,
        stepId,
        RunStepStatus.SKIPPED.name());
  }

  /**
   * Parks the run on an approval gate.
   *
   * <p>The step is persisted as {@code PENDING} with no job id, which is what makes it visible in
   * the timeline and resolvable by {@code approve}. Parking without a row would leave the DAG
   * re-dispatching the same gate on every advance, and nothing for the approval to flip.
   */
  private void park(UUID runId, String stepId) {
    jdbcTemplate.update(
        "INSERT INTO studio_run_step (run_id, step_id, ordinal, status, started_at)"
            + " VALUES (?, ?, 0, ?, now()) ON CONFLICT (run_id, step_id, ordinal) DO NOTHING",
        runId,
        stepId,
        RunStepStatus.PENDING.name());
    jdbcTemplate.update(
        "UPDATE studio_run SET status = ? WHERE id = ? AND finished_at IS NULL",
        RunStatus.AWAITING_INPUT.name(),
        runId);
  }

  /** A skipped step unblocks its dependants immediately; re-entering keeps that in one path. */
  private void advanceLater(UUID runId) {
    advance(runId);
  }

  private boolean allTerminal(Blueprint blueprint, Map<String, RunStepStatus> states) {
    return blueprint.steps().stream()
        .allMatch(
            step -> {
              RunStepStatus state = states.get(step.id());
              return state != null
                  && state != RunStepStatus.PENDING
                  && state != RunStepStatus.RUNNING;
            });
  }

  private static boolean isTerminal(RunStatus status) {
    return status == RunStatus.SUCCEEDED
        || status == RunStatus.FAILED
        || status == RunStatus.CANCELLED;
  }

  /**
   * The worst state of each step across its fan-out instances.
   *
   * <p>Aggregated deliberately: a fan-out step is done only when every instance is, and it has
   * failed if any instance has — so one straggler cannot let the DAG advance past it.
   */
  /**
   * What to tell the user about a step that failed.
   *
   * <p>The step's own message when it has one, and {@code step '<id>' failed} only when it does
   * not. A scope guard's refusal is a sentence the pack wrote for the reader (ADR-051), and the
   * generic wording used to throw it away — along with every other cause the executor had already
   * taken the trouble to record.
   */
  private String stepFailureMessage(UUID runId, String stepId) {
    List<String> messages =
        jdbcTemplate.queryForList(
            """
            SELECT error_message FROM studio_run_step
             WHERE run_id = ? AND step_id = ? AND status = 'FAILED'
               AND error_message IS NOT NULL AND error_message <> ''
             ORDER BY finished_at NULLS LAST
             LIMIT 1
            """,
            String.class,
            runId,
            stepId);
    return messages.isEmpty() ? "step '" + stepId + "' failed" : messages.getFirst();
  }

  /**
   * The cause each failed step declared, worst-case per fan-out instance.
   *
   * <p>A step whose row predates ADR-053, or whose executor declared nothing, reads as {@link
   * FailureCause#EXECUTOR_FAULT} — the reading that releases the hold and blames nobody. Where two
   * instances of one fan-out failed differently, the run takes the cause that costs the actor
   * least: attributing a whole run to the user because one of five instances said so would be an
   * inference dressed as a declaration.
   */
  private Map<String, FailureCause> stepCauses(UUID runId) {
    Map<String, FailureCause> causes = new LinkedHashMap<>();
    jdbcTemplate
        .query(
            "SELECT step_id, failure_cause FROM studio_run_step"
                + " WHERE run_id = ? AND status = 'FAILED'",
            (rs, rowNum) ->
                Map.entry(rs.getString("step_id"), FailureCause.of(rs.getString("failure_cause"))),
            runId)
        .forEach(
            entry ->
                causes.merge(
                    entry.getKey(),
                    entry.getValue(),
                    (left, right) ->
                        left.settlesMeasuredWork() && !right.settlesMeasuredWork() ? right : left));
    return causes;
  }

  private Map<String, RunStepStatus> stepStates(UUID runId) {
    Map<String, RunStepStatus> states = new LinkedHashMap<>();
    jdbcTemplate
        .query(
            "SELECT step_id, status FROM studio_run_step WHERE run_id = ?",
            (rs, rowNum) ->
                Map.entry(rs.getString("step_id"), RunStepStatus.valueOf(rs.getString("status"))),
            runId)
        .forEach(entry -> states.merge(entry.getKey(), entry.getValue(), RunSagaService::worst));
    return states;
  }

  private static RunStepStatus worst(RunStepStatus left, RunStepStatus right) {
    if (left == RunStepStatus.FAILED || right == RunStepStatus.FAILED) {
      return RunStepStatus.FAILED;
    }
    if (left == RunStepStatus.RUNNING || right == RunStepStatus.RUNNING) {
      return RunStepStatus.RUNNING;
    }
    if (left == RunStepStatus.PENDING || right == RunStepStatus.PENDING) {
      return RunStepStatus.PENDING;
    }
    return left;
  }

  private Optional<StepRef> locateStep(String jobId) {
    return jdbcTemplate
        .query(
            "SELECT run_id, step_id, ordinal FROM studio_run_step WHERE job_id = ?",
            (rs, rowNum) ->
                new StepRef(
                    rs.getObject("run_id", UUID.class),
                    rs.getString("step_id"),
                    rs.getInt("ordinal")),
            jobId)
        .stream()
        .findFirst();
  }

  private RunRow loadRun(UUID runId) {
    return jdbcTemplate
        .query(
            "SELECT id, installation_id, actor_id, studio_key, blueprint_version, status, hold_id,"
                + " correlation_id, inputs, data_class FROM studio_run WHERE id = ?",
            (rs, rowNum) ->
                new RunRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("installation_id", UUID.class),
                    rs.getString("actor_id"),
                    rs.getString("studio_key"),
                    rs.getString("blueprint_version"),
                    RunStatus.valueOf(rs.getString("status")),
                    rs.getString("hold_id"),
                    rs.getString("correlation_id"),
                    columns.objectMap(rs.getString("inputs")),
                    DataClass.valueOf(rs.getString("data_class"))),
            runId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** Where a job outcome lands in a DAG. */
  private record StepRef(UUID runId, String stepId, int ordinal) {}

  /** The run row the saga reasons over. */
  private record RunRow(
      UUID id,
      UUID installationId,
      String actorId,
      String studioKey,
      String blueprintVersion,
      RunStatus status,
      String holdId,
      String correlationId,
      Map<String, Object> inputs,
      DataClass dataClass) {}
}
