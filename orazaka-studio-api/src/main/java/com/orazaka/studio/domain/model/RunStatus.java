package com.orazaka.studio.domain.model;

/**
 * Lifecycle of one execution of an installation.
 *
 * <p>A run holds credits from the moment it starts, so <b>every</b> path out of a non-terminal
 * state must settle or release that hold. A run stuck outside a terminal state past twice the step
 * timeout is the failure mode ADR-034 singles out — it silently freezes a paying actor's balance,
 * and it never shows up in a happy-path test.
 *
 * @see RunStepStatus for the per-step vocabulary, which is deliberately different
 */
public enum RunStatus {

  /** Parked before any spend: an approval gate that precedes execution. */
  PENDING_APPROVAL,

  /** Steps are in flight. The hold is outstanding. */
  RUNNING,

  /** Parked mid-DAG on an {@link StepKind#APPROVAL} step. The hold stays outstanding. */
  AWAITING_INPUT,

  /** Terminal, green. The hold was settled against measured consumption. */
  SUCCEEDED,

  /** Terminal, red. The hold was released — a failed run is never billed. */
  FAILED,

  /**
   * Terminal, cancelled by the actor. The hold was released; in-flight jobs finish and are
   * discarded.
   */
  CANCELLED,

  /**
   * Unwinding a failure: partial artefacts are being purged before the run reaches {@link #FAILED}.
   */
  COMPENSATING
}
