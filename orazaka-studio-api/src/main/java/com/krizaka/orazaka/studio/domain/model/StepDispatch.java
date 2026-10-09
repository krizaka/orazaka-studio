package com.krizaka.orazaka.studio.domain.model;

import com.krizaka.orazaka.jobs.domain.model.DataClass;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A fully resolved step, ready to be submitted to the job plane.
 *
 * <p>The hand-off between the DAG interpreter and its execution adapter. Every template is already
 * rendered, which is what keeps the adapter dumb: it publishes a job command and returns a job id,
 * and it never needs the scope, the blueprint or the installation.
 *
 * <p>Nothing here is a secret. Connector credentials stay inside the run scope; a dispatch that
 * carried them would put them into a broker payload, a DLQ message and every log line that traces
 * one (ADR-034 §18).
 *
 * <p><b>No {@code holdId}, deliberately (ADR-041).</b> It carried the RUN's hold until phase D's
 * successor, which meant every step's outcome event looked billable to {@code
 * JobSettlementListener}: the first step to finish settled the whole run's hold at the AGENT/CALL
 * rate — a constant quantity of 1, five credits — and every later step found it closed. A run that
 * reserved 480 credits was debited 5. The hold is the run's, so only the run's saga may close it; a
 * step that cannot see it cannot settle it by accident.
 *
 * @param runId the run this dispatch belongs to
 * @param stepId the blueprint step being executed
 * @param ordinal the fan-out index; {@code 0} for a single-shot step
 * @param featureKey OPAQUE capability key the job plane routes on, {@code null} for a connector
 *     step
 * @param connectorType OPAQUE connector key, non-null exactly when this step dispatches to the
 *     automation plane rather than the job plane — the adapter's routing discriminator
 * @param resolvedInputs the step's inputs with every placeholder already rendered; defensively
 *     copied
 * @param actorId OPAQUE billable subject, carried so the executor can resolve their context
 * @param correlationId ties this job back to its run
 * @param brandContext the installation's configuration — brand name, tone, signature — carried so
 *     the pipeline can enrich every step's prompt with the actor's voice without each blueprint
 *     template repeating it (ADR-034 §9.1); defensively copied
 * @param dataClass the run's data class; the job this step becomes carries it and is purged by it
 *     (ADR-065)
 */
public record StepDispatch(
    UUID runId,
    String stepId,
    int ordinal,
    String featureKey,
    String connectorType,
    Map<String, Object> resolvedInputs,
    String actorId,
    String correlationId,
    Map<String, String> brandContext,
    Optional<PackScopeGuard> scopeGuard,
    Optional<String> crisisTerms,
    Optional<String> crisisResponse,
    Optional<String> guardSubject,
    DataClass dataClass) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public StepDispatch {
    // Optional, not null: a STANDARD pack declares no domain and the difference between "no guard"
    // and "a guard nobody filled in" must not be a null check somebody forgets (ADR-051).
    scopeGuard = scopeGuard == null ? Optional.empty() : scopeGuard;
    // Both halves of the crisis declaration or neither: terms with no response short-circuit into
    // silence, which for a crisis guard means a person in crisis reading an empty reply (ADR-055).
    crisisTerms = crisisTerms == null ? Optional.empty() : crisisTerms;
    crisisResponse = crisisResponse == null ? Optional.empty() : crisisResponse;
    // What the user wrote, as opposed to the template it was pasted into. Only this producer can
    // tell the two apart, so it says which is which (ADR-055 §7).
    guardSubject = guardSubject == null ? Optional.empty() : guardSubject;
    // The run's class, carried to the job plane so the job it becomes is purged by the same class
    // (ADR-065). Required: a step dispatched with no class would reach the job plane as work nobody
    // classified, and the job plane refuses that rather than reading it as STANDARD.
    Objects.requireNonNull(dataClass, "a dispatch must carry its run's data class");
    if (crisisTerms.isPresent() != crisisResponse.isPresent()) {
      throw new IllegalArgumentException(
          "a crisis declaration must carry both its terms and its reviewed response");
    }
    Objects.requireNonNull(runId, "runId must not be null");
    if (stepId == null || stepId.isBlank()) {
      throw new IllegalArgumentException("stepId must not be blank");
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must be >= 0, was: " + ordinal);
    }
    // Exactly one target: a step goes to the job plane or to the automation plane, never both and
    // never neither — a dispatch that named neither would be published nowhere and awaited forever.
    boolean hasFeature = featureKey != null && !featureKey.isBlank();
    boolean hasConnector = connectorType != null && !connectorType.isBlank();
    if (hasFeature == hasConnector) {
      throw new IllegalArgumentException(
          "a dispatch must name exactly one of featureKey or connectorType");
    }
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    resolvedInputs = resolvedInputs == null ? Map.of() : Map.copyOf(resolvedInputs);
    brandContext = brandContext == null ? Map.of() : Map.copyOf(brandContext);
  }
}
