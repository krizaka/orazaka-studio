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
  TRANSFORM
}
