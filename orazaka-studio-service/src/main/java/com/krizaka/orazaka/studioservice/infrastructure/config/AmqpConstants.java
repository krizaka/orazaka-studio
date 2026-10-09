package com.krizaka.orazaka.studioservice.infrastructure.config;

/**
 * RabbitMQ topology for the studio service (AGENTS.md §6).
 *
 * <p>Contract copy: the topic exchanges are shared, stable contracts declared by the platform. This
 * service owns only its saga queue and that queue's DLQ.
 *
 * <p>The Studio context reuses the existing job plane rather than declaring an exchange of its own
 * (ADR-034 §19): a second bus would duplicate the SSE relay, the dedup ledger and the DLQ topology
 * for no behavioural gain, and it would contradict ADR-032's "the exchanges are the API".
 */
final class AmqpConstants {

  private AmqpConstants() {}

  /** Work requests travel here: the saga publishes a JobCommand and the job service consumes it. */
  static final String JOBS_EXCHANGE = "orazaka.jobs";

  /** Terminal outcomes come back here, where the SSE relay is already listening. */
  static final String EVENTS_EXCHANGE = "orazaka.events";

  static final String DLX_EXCHANGE = "orazaka.dlx";

  /** This service's own saga queue — the only queue it owns. */
  static final String SAGA_QUEUE = "orazaka.events.studio";

  static final String SAGA_DLQ = SAGA_QUEUE + ".dlq";

  /** Terminal job outcomes only: progress events do not advance a DAG. */
  static final String DONE_BINDING = "job.*.done";

  static final String ERROR_BINDING = "job.*.error";

  /**
   * Connector steps run on the automation plane, which reports on its own event rather than {@code
   * job.*.done}. A separate queue rather than a third binding on the saga queue: the two payloads
   * have different shapes, and a tolerant reader that had to guess which one it received would
   * eventually read a FAILED telemetry as a success.
   */
  static final String CONNECTOR_QUEUE = "orazaka.events.studio.connector";

  static final String CONNECTOR_DLQ = CONNECTOR_QUEUE + ".dlq";

  static final String CONNECTOR_BINDING = "evt.automation.telemetry";

  /**
   * Dunning (design §14): a lapsed subscription pauses this actor's installations, and a resumed
   * one revives them. Its own queue, because the payload is a commercial announcement rather than
   * an execution outcome and shares nothing with either.
   */
  static final String SUBSCRIPTION_QUEUE = "orazaka.events.studio.subscription";

  static final String SUBSCRIPTION_DLQ = SUBSCRIPTION_QUEUE + ".dlq";

  static final String SUBSCRIPTION_BINDING = "evt.subscription.*";

  /**
   * Capability-row changes, so a withdrawn capability stops dispatching immediately rather than at
   * the end of a cache window (ADR-038). Its own queue for the same reason the subscription cache
   * has one: every host that caches keeps its own copy, or competing consumers would leave exactly
   * one cache correct.
   */
  static final String CAPABILITY_QUEUE = "orazaka.events.studio.capability";

  static final String CAPABILITY_DLQ = CAPABILITY_QUEUE + ".dlq";

  static final String CAPABILITY_BINDING = "evt.capability.*";
}
