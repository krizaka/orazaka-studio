package com.orazaka.studio.domain.exception;

/**
 * Thrown when a blueprint cannot be statically validated, and therefore must never be persisted.
 *
 * <p>This is the anti-corruption boundary of the whole Studio context (ERR-127). A blueprint is
 * untrusted input <b>even from an admin</b>: it is parsed into validated records at the edge, so
 * the run path downstream needs no defensive checks and no generic {@code Map<String, Object>} ever
 * reaches business logic.
 *
 * <p>{@link #stepId()} carries the offending node so the authoring UI can point at the exact step
 * rather than saying "invalid blueprint". It is {@code null} only for a violation that belongs to
 * no single node — an empty step list, or a cycle, which by definition is not one step's fault.
 */
public class BlueprintValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String stepId;

  /**
   * Constructs a violation attributable to one step.
   *
   * @param stepId the offending step's id
   * @param message what is wrong with it
   */
  public BlueprintValidationException(String stepId, String message) {
    super(stepId == null ? message : "step '" + stepId + "': " + message);
    this.stepId = stepId;
  }

  /**
   * Constructs a violation of the graph as a whole.
   *
   * @param message what is wrong with the blueprint
   */
  public BlueprintValidationException(String message) {
    this(null, message);
  }

  /**
   * @return the offending step's id, or {@code null} when the violation is graph-wide
   */
  public String stepId() {
    return stepId;
  }
}
