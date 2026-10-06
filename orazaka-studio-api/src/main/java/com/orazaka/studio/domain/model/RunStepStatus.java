package com.orazaka.studio.domain.model;

/**
 * Lifecycle of one node of a run's DAG — including one fan-out instance of a node.
 *
 * <p>Deliberately <b>not</b> {@link RunStatus}: a step is never {@code PENDING_APPROVAL}, {@code
 * AWAITING_INPUT} or {@code COMPENSATING} (those describe the run), and a run is never {@code
 * SKIPPED} (that is what {@link ErrorPolicy#SKIP} does to a step). Sharing one enum would make half
 * of each vocabulary unrepresentable-but-legal, which is exactly the kind of drift a typed contract
 * exists to prevent.
 *
 * <p>The saga advances a step in a single conditional write — {@code … SET status=? WHERE run_id=?
 * AND step_id=? AND ordinal=? AND status='RUNNING'} — so these constants carry the idempotency of
 * at-least-once delivery, not merely a display label.
 */
public enum RunStepStatus {

  /** Persisted with the run, waiting for its dependencies to go green. Nothing was submitted. */
  PENDING,

  /** Submitted to the job plane; a {@code jobId} is recorded and the outcome is awaited. */
  RUNNING,

  /** Terminal, green. Its output is in scope for downstream steps. */
  SUCCEEDED,

  /** Terminal, red. Under {@link ErrorPolicy#FAIL} this fails the run. */
  FAILED,

  /**
   * Terminal, not executed: either its {@code condition} evaluated false, or it failed under {@link
   * ErrorPolicy#SKIP}. Distinct from {@link #FAILED} because a skipped step still leaves the run
   * billable for what ran.
   */
  SKIPPED,

  /** Terminal, abandoned because the run was cancelled before this step reached the job plane. */
  CANCELLED
}
