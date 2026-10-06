package com.orazaka.studio.domain.model;

/**
 * What the run does when a step fails.
 *
 * <p>The choice is also a billing decision (ADR-034 §8.4): a run that fails under {@link #FAIL}
 * releases its hold and is never charged, whereas a run that carries on past a {@link #SKIP} step
 * <b>is</b> charged for what actually ran — so the run detail must show which steps were billed.
 */
public enum ErrorPolicy {

  /** Fail the whole run, compensate, release the hold. The safe default. */
  FAIL,

  /** Drop this step's output and carry on — for genuinely optional enrichment. */
  SKIP,

  /** Re-submit up to the step's {@code maxAttempts}, then fail the run. */
  RETRY
}
