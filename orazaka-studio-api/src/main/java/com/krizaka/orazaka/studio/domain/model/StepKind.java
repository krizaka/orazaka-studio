package com.krizaka.orazaka.studio.domain.model;

/**
 * What a {@link BlueprintStep} does — the discriminant the interpreter dispatches on.
 *
 * <p>This enum is the extension point of the whole DSL. Because the templating grammar is
 * deliberately not Turing-complete (ADR-034 §1), capability the grammar cannot express becomes a
 * <b>new kind here</b>, which is reviewable, rather than a new expression form, which is a
 * sandbox-escape surface no amount of review makes safe.
 */
public enum StepKind {

  /**
   * The default: a {@code feature_key} from the capability registry, executed as a job on the
   * existing job plane. Requires a {@code featureKey}.
   */
  CAPABILITY,

  /**
   * Retrieval against the actor's own RAG sources — grounds a downstream step in their material.
   */
  KNOWLEDGE,

  /** An outbound integration executed with the actor's stored connector credentials. */
  CONNECTOR,

  /** No execution: the run parks awaiting a human decision, then resumes. */
  APPROVAL,

  /**
   * In-process, pure, declarative reshaping (pick / join / format). <b>No expression evaluation, no
   * scripting</b> — that is the point of having a kind rather than an expression language.
   */
  TRANSFORM,

  /**
   * A call to another published Studio (orazaka-studio#62): the step starts a child run with its
   * own run id, its credits inside the parent's hold, and its outcome becomes this step's. Requires
   * a {@code studioKey}. One level only — a Studio that calls Studios cannot itself be called, so a
   * composition can never recurse.
   */
  STUDIO,

  /**
   * A session a client drives (ADR-093, krizaka/orazaka#450): no executor, the step stays {@code
   * RUNNING} while the client sends turns and the run keeps its hold. It ends on the client's «
   * finish », or after an idle TTL read from {@code session.idle-ttl-seconds}; the run then settles
   * like any other, and a finished, cancelled or expired session takes no more turns.
   */
  SESSION
}
