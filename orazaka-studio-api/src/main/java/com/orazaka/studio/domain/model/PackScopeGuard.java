package com.orazaka.studio.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * The domain a pack refuses to answer in, declared by the pack (ADR-051).
 *
 * <p><b>The engine holds the mechanism and never the subject.</b> A legal-drafting pack refusing
 * legal advice and a medical pack refusing diagnosis are one interceptor and two rows — naming
 * either domain in engine code would be the fourth instance of the coupling AGENTS.md §12 forbids,
 * and the first three each cost a defect before they were found.
 *
 * <p><b>What this is not.</b> Term matching is a guard, not a classifier: it catches the question
 * asked plainly and misses the one asked obliquely. That is worth having anyway, because the
 * alternative it replaces is a sentence in a prompt — and a prompt instruction is not a control
 * (PACK_CATALOGUE §6.2). A pack whose domain needs more than this should not be SENSITIVE.
 *
 * @param refusedTerms the terms whose presence refuses the turn; matched case-insensitively on word
 *     boundaries, never as substrings — "avocat" must not fire on "avocatier"
 * @param refusal the exact answer the user gets instead, written by the pack's author and never
 *     generated; a fixed response is the only kind that can be reviewed before it is given
 */
public record PackScopeGuard(List<String> refusedTerms, String refusal) {

  /** Compact canonical constructor enforcing the guard's invariants (ERR-106). */
  public PackScopeGuard {
    refusedTerms = refusedTerms == null ? List.of() : List.copyOf(refusedTerms);
    if (refusedTerms.isEmpty()) {
      throw new IllegalArgumentException("a scope guard with no refused term refuses nothing");
    }
    if (refusedTerms.stream().anyMatch(term -> term == null || term.isBlank())) {
      throw new IllegalArgumentException("a refused term must not be blank");
    }
    Objects.requireNonNull(refusal, "refusal must not be null");
    if (refusal.isBlank()) {
      // A blank refusal wouldshort-circuit into silence, which reads to a user as a broken product
      // rather than as a boundary the pack drew on purpose.
      throw new IllegalArgumentException("a scope guard must state what it answers instead");
    }
  }
}
