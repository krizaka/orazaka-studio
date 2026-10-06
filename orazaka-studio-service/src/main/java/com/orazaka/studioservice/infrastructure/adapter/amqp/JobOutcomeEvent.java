package com.orazaka.studioservice.infrastructure.adapter.amqp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.orazaka.jobs.domain.model.FailureCause;
import java.util.Map;

/**
 * The terminal outcome of a job, as the saga reads it off {@code job.{id}.done|error}.
 *
 * <p>Anti-corruption layer (ERR-127): the broker payload becomes a validated record at the boundary
 * so no raw map reaches the interpreter.
 *
 * <p>Tolerant reader, deliberately: this event is a broadcast serving the SSE relay and billing
 * too, and each reads keys the others ignore. A producer adding a field for another consumer must
 * never dead-letter a DAG advance — that would strand a run with its hold outstanding.
 *
 * @param jobId the job this outcome belongs to
 * @param result what the executor produced, absent on failure
 * @param consumption the raw measurements the executor reported — frames, tokens, steps — which the
 *     saga stores per step and settles as one sum at the end of the run (ADR-041). Never credits
 *     and never a unit: pricing is billing's, and this context does not own the pricebook
 * @param model the engine that produced the result, the other half of the pricebook key beside the
 *     step's capability; {@code null} when the producer chose none, which prices against the
 *     capability's default row
 * @param error the failure message; its presence is what makes this a failure
 * @param cause why it failed, <b>declared</b> by the executor that failed it (ADR-053). Read
 *     through {@link #failureCause()} and never off {@code error}: a producer that says nothing is
 *     an executor fault, which releases the hold and blames nobody
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobOutcomeEvent(
    String jobId,
    Map<String, Object> result,
    Map<String, Object> consumption,
    String model,
    String error,
    String cause) {

  /**
   * The declared cause, or {@link FailureCause#EXECUTOR_FAULT} when none was declared.
   *
   * <p>The whole point of the contract is that this method never looks at {@link #error()}. A
   * producer that has not been taught to declare — an older worker, a third-party one — degrades to
   * the cause that costs the actor nothing, rather than to whichever category its prose happened to
   * resemble.
   *
   * @return the cause, never {@code null}
   */
  public FailureCause failureCause() {
    return FailureCause.of(cause);
  }

  /**
   * @return whether the job failed
   */
  public boolean failed() {
    return error != null && !error.isBlank();
  }

  /**
   * @return the output, never null — a success that reported nothing is still a success
   */
  public Map<String, Object> outputOrEmpty() {
    return result == null ? Map.of() : result;
  }

  /**
   * @return what was measured, never null — a success that measured nothing settles nothing
   */
  public Map<String, Object> consumptionOrEmpty() {
    return consumption == null ? Map.of() : consumption;
  }
}
