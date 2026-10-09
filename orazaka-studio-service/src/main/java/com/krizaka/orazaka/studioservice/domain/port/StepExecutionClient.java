package com.krizaka.orazaka.studioservice.domain.port;

import com.krizaka.orazaka.studio.domain.model.StepDispatch;

/**
 * Outbound port: hand one fully-resolved step to whatever executes it.
 *
 * <p>Declared by the interpreter and implemented in {@code infrastructure/adapter/amqp}, so the DAG
 * logic never names a broker, an exchange or a routing key. That is also what makes the engine
 * testable without RabbitMQ — the phase-3 acceptance run is an engine test, and it should fail only
 * for engine reasons.
 */
public interface StepExecutionClient {

  /**
   * Submits a step for execution.
   *
   * @param dispatch the step, with every template already rendered
   * @return the job id the outcome will arrive under
   */
  String submit(StepDispatch dispatch);
}
