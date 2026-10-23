package com.krizaka.orazaka.studioservice.infrastructure.support;

import com.krizaka.orazaka.studio.domain.model.BlueprintStep;
import com.krizaka.orazaka.studio.domain.model.StepKind;
import java.util.Optional;

/**
 * The citation shape of grounded research (ADR-087 §3), published as the output contract of the
 * capability {@code orazaka.core.research.grounded} (orazaka-studio#77).
 *
 * <p>A grounded answer is {@code answer}, {@code claims[]} — each with its {@code text}, a {@code
 * status} of {@code VERIFIED} or {@code TO_VALIDATE}, an optional {@code reason}, and {@code
 * citations[]} of {@code url}, {@code quote} (at most 300 characters), {@code contentHash} (a
 * SHA-256 in hex) and {@code fetchedAt} — and {@code sources[]} of {@code url}, {@code title} and
 * {@code sourceKey}. A step of that capability that declares no contract of its own is held to this
 * one by the saga, so an output outside the shape fails the step as {@code EXECUTOR_FAULT} like any
 * other contract breach.
 *
 * <p>Written within the keywords {@link OutputContract} enforces, and nothing else: {@link
 * OutputContract#unsupportedKeywords} of this schema is empty, which {@code CitationContractTest}
 * pins. The rule « a {@code VERIFIED} claim has a found citation » cannot be a schema keyword; the
 * engine's verifier enforces it.
 */
public final class CitationContract {

  /** The capability whose output this contract is. */
  public static final String FEATURE_KEY = "orazaka.core.research.grounded";

  /** The contract, as JSON text in the saga's enforced subset (ADR-076). */
  public static final String SCHEMA =
      """
      {"type":"object","required":["answer","claims","sources"],
       "properties":{
         "answer":{"type":"string"},
         "claims":{"type":"array","items":{"type":"object","required":["text","status","citations"],
           "properties":{
             "text":{"type":"string","minLength":1},
             "status":{"type":"string","enum":["VERIFIED","TO_VALIDATE"]},
             "reason":{"type":"string"},
             "citations":{"type":"array","items":{"type":"object",
               "required":["url","quote","contentHash","fetchedAt"],
               "properties":{
                 "url":{"type":"string","minLength":8},
                 "quote":{"type":"string","minLength":1,"maxLength":300},
                 "contentHash":{"type":"string","minLength":64,"maxLength":64},
                 "fetchedAt":{"type":"string"}}}}}}},
         "sources":{"type":"array","items":{"type":"object","required":["url","title","sourceKey"],
           "properties":{
             "url":{"type":"string"},
             "title":{"type":"string"},
             "sourceKey":{"type":"string"}}}}}}
      """
          .strip();

  private CitationContract() {}

  /**
   * The contract a step is held to when it declares none: this one for a {@link
   * StepKind#CAPABILITY} step of {@link #FEATURE_KEY}, nothing for any other step.
   *
   * @param step the blueprint step
   * @return the schema as JSON text, or empty
   */
  public static Optional<String> defaultFor(BlueprintStep step) {
    return step.kind() == StepKind.CAPABILITY && FEATURE_KEY.equals(step.featureKey())
        ? Optional.of(SCHEMA)
        : Optional.empty();
  }
}
