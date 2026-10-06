package com.orazaka.studioservice.infrastructure.adapter.amqp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The automation plane's telemetry, as the saga reads it off {@code evt.automation.telemetry}.
 *
 * <p>A different shape from a job outcome, which is why it has its own queue: automation reports a
 * <b>status</b> rather than an error field, and a reader that had to infer which of the two shapes
 * it received would read a FAILED telemetry as a success the first time the payload drifted.
 *
 * <p>Tolerant reader: this event also feeds the automation dashboard, which reads keys the saga
 * ignores. A producer adding one must never dead-letter a DAG advance.
 *
 * @param jobId the connector job this telemetry belongs to
 * @param status RUNNING, COMPLETED or FAILED
 * @param message the human-readable detail, carried into the run's error when it failed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectorOutcomeEvent(String jobId, String status, String message) {

  /**
   * @return whether this telemetry is terminal — a RUNNING heartbeat advances nothing
   */
  public boolean terminal() {
    return "COMPLETED".equals(status) || "FAILED".equals(status);
  }

  /**
   * @return whether the connector failed
   */
  public boolean failed() {
    return "FAILED".equals(status);
  }
}
