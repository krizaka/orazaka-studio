package com.krizaka.orazaka.studioservice.infrastructure.support;

import java.io.Serial;
import java.util.List;

/**
 * A run produced a {@code JSON} output its blueprint's contract refuses (ADR-076).
 *
 * <p>The message holds paths and rules only: the value is what a person said, and this message
 * becomes the run's error.
 */
public class OutputContractException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final List<String> violations;

  /**
   * @param violations what the value breaks, one path and rule each
   */
  public OutputContractException(List<String> violations) {
    super(
        "output breaks its declared contract: "
            + String.join("; ", violations.stream().limit(5).toList()));
    this.violations = List.copyOf(violations);
  }

  /**
   * @return every violation found
   */
  public List<String> violations() {
    return violations;
  }
}
