package com.krizaka.orazaka.studioservice.domain.model;

import java.util.List;
import java.util.Set;

/**
 * The outcome of the content policy for one prompt.
 *
 * @param labels what the classifier found ({@code NONE} only when nothing else was)
 * @param refused whether the run must not start
 * @param rule the rule that decided, stable and machine-readable ({@code minors_risk.always},
 *     {@code tenant.refuse.SEXUAL_EXPLICIT}, {@code
 *     provider.job.video.generate.allows_adult_content}); {@code permissive.default} when nothing
 *     refused
 * @param warnings provider terms that the operator marked as WARN rather than BLOCK
 * @param providers the capabilities (providers) the run would reach
 */
public record ContentDecision(
    Set<ContentLabel> labels,
    boolean refused,
    String rule,
    List<String> warnings,
    List<String> providers) {

  /** The stable error code a refusal answers with; the UI translates it. */
  public static final String REFUSED_CODE = "content.policy_refused";

  public ContentDecision {
    labels = Set.copyOf(labels);
    warnings = List.copyOf(warnings);
    providers = List.copyOf(providers);
  }

  /** The audit verb for this decision. */
  public String verb() {
    return refused ? "REFUSED" : (warnings.isEmpty() ? "ALLOWED" : "WARNED");
  }
}
