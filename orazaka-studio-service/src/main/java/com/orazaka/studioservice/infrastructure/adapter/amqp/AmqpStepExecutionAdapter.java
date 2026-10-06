package com.orazaka.studioservice.infrastructure.adapter.amqp;

import com.orazaka.jobs.domain.exception.UnroutableCapabilityException;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studio.domain.model.StepDispatch;
import com.orazaka.studioservice.application.service.OutboxService;
import com.orazaka.studioservice.domain.port.StepExecutionClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Submits a step onto the existing job plane.
 *
 * <p>The Studio service never calls the job service over HTTP: it publishes a {@code JobCommand}
 * and consumes the outcome event — the exchanges <b>are</b> the API (ADR-032, ADR-034 §3). That is
 * also what gives Studio runs live per-step progress for free, since the conversation service's SSE
 * relay already listens to the same events.
 *
 * <p>The payload carries {@code runId} and {@code stepId} so the outcome can be correlated back to
 * a node of a DAG. The executor ignores them, which is the point: it stays a capability executor
 * and learns nothing about Studios.
 *
 * <p>Where a step goes is <b>read</b>, never derived. This class used to answer that question with
 * a {@code contains()} chain over the feature key, which meant a capability the chain did not
 * recognise was published to the text queue and failed there — or was answered by an LLM that had
 * no idea what it had been asked (ADR-037 §2.1). There is now no default branch: a capability with
 * no enabled route raises {@link UnroutableCapabilityException} and nothing is published.
 */
@Component
class AmqpStepExecutionAdapter implements StepExecutionClient {

  private static final Logger logger = LoggerFactory.getLogger(AmqpStepExecutionAdapter.class);

  /**
   * Routing keys of the job plane. Contract copy of {@code MessagingContract} — that class lives in
   * {@code com.orazaka.persistence}, another context's Tier-3, which SEAM-002 forbids importing.
   * The AMQP contract tests are what keep the copies honest.
   */
  private static final String JOBS_EXCHANGE = "orazaka.jobs";

  /**
   * Connector steps go to the automation plane, which owns the actor's stored credentials. The
   * Studio service never sees a secret: it names the connector and the automation service resolves
   * it (ADR-034 §18).
   */
  private static final String AUTOMATION_ROUTING_KEY = "job.automation.approved";

  /**
   * The executor resolves the concrete model from the capability row; this asks for its default.
   */
  private static final String DEFAULT_MODEL = "default";

  /** Preference namespace the brand-context interceptor reads (ADR-034 §9.1). */
  private static final String BRAND_PREFIX = "orazaka.studio.brand.";

  private final OutboxService outboxService;
  private final CapabilityRoutingClient capabilityRoutingClient;

  AmqpStepExecutionAdapter(
      OutboxService outboxService, CapabilityRoutingClient capabilityRoutingClient) {
    this.outboxService = Objects.requireNonNull(outboxService, "OutboxService cannot be null");
    this.capabilityRoutingClient =
        Objects.requireNonNull(capabilityRoutingClient, "CapabilityRoutingClient cannot be null");
  }

  /**
   * {@inheritDoc}
   *
   * @throws UnroutableCapabilityException when the step's capability has no enabled route — raised
   *     <b>before</b> the message is built, so nothing reaches a queue
   */
  @Override
  public String submit(StepDispatch dispatch) {
    String jobId = UUID.randomUUID().toString();
    if (dispatch.connectorType() != null) {
      return submitToAutomation(dispatch, jobId);
    }

    // Resolved first, so an unroutable capability costs a lookup and not a message on a queue
    // nothing drains.
    CapabilityRoute route =
        capabilityRoutingClient
            .route(dispatch.featureKey())
            .orElseThrow(
                () ->
                    new UnroutableCapabilityException(
                        dispatch.featureKey(),
                        "step '" + dispatch.stepId() + "' of run " + dispatch.runId()));

    Map<String, Object> payload = new HashMap<>(dispatch.resolvedInputs());
    payload.put("runId", dispatch.runId().toString());
    payload.put("stepId", dispatch.stepId());
    payload.put("ordinal", dispatch.ordinal());
    payload.put("correlationId", dispatch.correlationId());
    // NO holdId, ever (ADR-041). The run's hold is the run's, and a step that carried it was a
    // step that could settle it: JobSettlementListener bills any outcome whose payload names a
    // hold, so the first step to finish closed the whole run at the AGENT/CALL rate and the rest
    // ran free. The steps report their consumption in job.{id}.done; the saga sums it and settles
    // once, at the end, against the rates of the capabilities that actually ran.
    // The actor's brand kit travels under the orazaka.studio.* preference namespace so the pipeline
    // can enrich every step with their voice without each blueprint template repeating it
    // (ADR-034 §9.1). Namespaced rather than flat: these keys share a Context with user
    // preferences.
    // Declared, not assumed: the engine enriches the namespace a producer names, and this is the
    // Studio's (ADR-050). A pack that wants a context of its own sets this key among its step's
    // inputs and the same interceptor serves it, with no engine change.
    dispatch.brandContext().forEach((key, value) -> payload.put(BRAND_PREFIX + key, value));
    if (!dispatch.brandContext().isEmpty()) {
      payload.put(JobCommand.ENRICHMENT_NAMESPACE_KEY, BRAND_PREFIX);
    }
    // The domain this run's pack refuses, declared per step so the guard needs no lookup of its own
    // and no knowledge of packs (ADR-051). Absent for a STANDARD pack, which is every pack that
    // declares nothing — the installer refuses a SENSITIVE one that does.
    dispatch
        .scopeGuard()
        .ifPresent(
            guard -> {
              payload.put(
                  JobCommand.SCOPE_REFUSED_TERMS_KEY, String.join(",", guard.refusedTerms()));
              payload.put(JobCommand.SCOPE_REFUSAL_KEY, guard.refusal());
            });
    // The crisis declaration travels the same way and for the same reason. Both halves together —
    // the record refuses one without the other — because terms with no reviewed response would
    // short-circuit a person in crisis into silence (ADR-055 §4).
    dispatch
        .crisisTerms()
        .ifPresent(terms -> payload.put(JobCommand.SAFETY_CRISIS_TERMS_KEY, terms));
    dispatch
        .crisisResponse()
        .ifPresent(response -> payload.put(JobCommand.SAFETY_RESPONSE_KEY, response));
    dispatch
        .guardSubject()
        .ifPresent(subject -> payload.put(JobCommand.GUARD_SUBJECT_KEY, subject));
    // "This inference is metered by whoever orchestrates it." The pipeline's EntitlementInterceptor
    // takes its own CHAT hold per turn, on the stated assumption that a turn is synchronous and has
    // no async job behind it — false for a Studio step, which is exactly that. The result was every
    // step billed twice: once per inference, once again in the run's aggregate settle (ADR-044).
    // Namespaced under `orazaka.metering.` rather than `orazaka.studio.` so no pack namespace
    // reaches engine code that has to read it [PACK-002].
    payload.put(JobCommand.DEFERRED_METERING_KEY, Boolean.TRUE);

    Map<String, Object> command = new HashMap<>();
    command.put("jobId", jobId);
    command.put("userId", dispatch.actorId());
    command.put("featureKey", dispatch.featureKey());
    command.put("model", DEFAULT_MODEL);
    command.put("payload", payload);
    // The run's class travels with the job it becomes, as a component of the command and not a
    // payload key: the job plane purges by it, and a payload key is something a door-1 caller can
    // write (ADR-065).
    command.put("dataClass", dispatch.dataClass().name());

    String routingKey = route.routingKey();
    // Through the outbox, in the saga's own transaction (ADR-067). This was `convertAndSend` from
    // inside `RunSagaService.advance`, which is @Transactional: a rollback afterwards left the job
    // running against a step row that no longer existed, and the hold it had taken lives in the
    // billing service, where a rollback here cannot reach it.
    outboxService.appendCommand(
        dispatch.runId().toString(), JOBS_EXCHANGE, routingKey, jobId, command);

    logger.debug(
        "Dispatched step {}[{}] of run {} as job {} on {}",
        dispatch.stepId(),
        dispatch.ordinal(),
        dispatch.runId(),
        jobId,
        routingKey);
    return jobId;
  }

  /**
   * Publishes a connector step onto the automation plane.
   *
   * <p>A different payload shape and a different outcome event, because it is a different executor
   * — automation answers on {@code evt.automation.telemetry}, not {@code job.*.done}. The saga
   * correlates both by the same job id, so the DAG does not care which plane ran a step.
   */
  private String submitToAutomation(StepDispatch dispatch, String jobId) {
    Map<String, Object> payload = new HashMap<>(dispatch.resolvedInputs());
    payload.put("runId", dispatch.runId().toString());
    payload.put("stepId", dispatch.stepId());

    Map<String, Object> command = new HashMap<>();
    command.put("jobId", jobId);
    command.put("userId", dispatch.actorId());
    command.put("connectorType", dispatch.connectorType());
    command.put("action", String.valueOf(dispatch.resolvedInputs().getOrDefault("action", "send")));
    command.put("status", "APPROVED");
    command.put("payload", payload);

    outboxService.appendCommand(
        dispatch.runId().toString(), JOBS_EXCHANGE, AUTOMATION_ROUTING_KEY, jobId, command);
    logger.debug(
        "Dispatched connector step {} of run {} as job {} to {}",
        dispatch.stepId(),
        dispatch.runId(),
        jobId,
        dispatch.connectorType());
    return jobId;
  }
}
