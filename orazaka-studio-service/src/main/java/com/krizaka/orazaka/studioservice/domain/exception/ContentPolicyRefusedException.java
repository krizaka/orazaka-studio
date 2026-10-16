package com.krizaka.orazaka.studioservice.domain.exception;

import com.krizaka.orazaka.studioservice.domain.model.ContentDecision;
import com.krizaka.orazaka.studioservice.domain.model.ContentLabel;
import java.util.Set;

/**
 * Thrown when the content policy refuses a run before anything is held, stored or sent to a
 * provider. Carries the label and the rule, never the prompt.
 */
public class ContentPolicyRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ContentDecision decision;

  /**
   * @param decision the refusing decision
   */
  public ContentPolicyRefusedException(ContentDecision decision) {
    super(ContentDecision.REFUSED_CODE + " (" + decision.rule() + ")");
    this.decision = decision;
  }

  /**
   * @return the decision, for the error body
   */
  public ContentDecision decision() {
    return decision;
  }

  /**
   * @return the labels that were found
   */
  public Set<ContentLabel> labels() {
    return decision.labels();
  }
}
