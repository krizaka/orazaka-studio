package com.krizaka.orazaka.studioservice.infrastructure.support;

import com.krizaka.orazaka.studioservice.domain.model.ContentLabel;
import java.text.Normalizer;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The local classifier: rules over folded text, in this JVM. The prompt never leaves the server to
 * be classified. A local-model classifier can be added behind the same {@link #classify} contract;
 * these rules stay as its floor so that {@code minors_risk} never depends on a model being up.
 *
 * <p>The vocabulary is deliberately small and ordinary — the terms any moderation list opens with —
 * and an operator extends it from the database (see {@code content_policy_rule}). It labels; it
 * does not decide.
 */
@Component
public class RulesContentClassifier {

  private static final Pattern MARKS = Pattern.compile("\\p{M}+");

  private static final Pattern EXPLICIT =
      word(
          "porn",
          "porno",
          "pornographic",
          "pornography",
          "hardcore",
          "xxx",
          "intercourse",
          "penetration",
          "blowjob",
          "cumshot",
          "orgasm",
          "genitals",
          "masturbat\\w*",
          "explicit sex\\w*",
          "sexually explicit",
          "sex act\\w*",
          "pornographique");
  private static final Pattern SUGGESTIVE =
      word(
          "nude",
          "nudes",
          "naked",
          "nudity",
          "lingerie",
          "erotic",
          "erotica",
          "sensual",
          "topless",
          "seductive",
          "nsfw",
          "sexy",
          "erotique");
  private static final Pattern VIOLENCE =
      word(
          "gore",
          "gory",
          "beheading",
          "decapitat\\w*",
          "dismember\\w*",
          "mutilat\\w*",
          "disembowel\\w*",
          "graphic violence",
          "torture",
          "eviscerat\\w*");
  private static final Pattern REAL_PERSON =
      word(
          "deepfake",
          "face[- ]?swap",
          "likeness of",
          "celebrity",
          "celebrite",
          "photorealistic (?:portrait )?of (?:the )?(?:president|actor|actress|singer|politician)");
  private static final Pattern MINOR_TERM =
      word(
          "child",
          "children",
          "kid",
          "kids",
          "minor",
          "underage",
          "under-age",
          "teen",
          "teenager",
          "schoolgirl",
          "schoolboy",
          "little girl",
          "little boy",
          "preteen",
          "toddler",
          "enfant",
          "enfants",
          "mineur",
          "mineure",
          "fillette",
          "garconnet");
  private static final Pattern MINOR_AGE =
      Pattern.compile(
          "\\b(?:[0-9]|1[0-7])[- ]?(?:years?|yrs?|ans)[- ]?(?:old|vieux)?\\b|\\b(?:[0-9]|1[0-7])\\s?yo\\b");

  /** Terms that are a minors-risk signal on their own. */
  private static final Pattern MINOR_STANDALONE = word("loli", "lolicon", "shota", "jailbait");

  private static Pattern word(String... alternatives) {
    return Pattern.compile("\\b(?:" + String.join("|", alternatives) + ")\\b");
  }

  /**
   * Labels a prompt.
   *
   * @param text what the actor wrote
   * @param operatorRules extra patterns the operator added, by label
   * @return the labels found; {@code {NONE}} when nothing matched
   */
  public Set<ContentLabel> classify(String text, Map<ContentLabel, List<Pattern>> operatorRules) {
    String folded = fold(text);
    Set<ContentLabel> found = EnumSet.noneOf(ContentLabel.class);
    boolean explicit = EXPLICIT.matcher(folded).find();
    boolean suggestive = SUGGESTIVE.matcher(folded).find();
    if (explicit) {
      found.add(ContentLabel.SEXUAL_EXPLICIT);
    }
    if (suggestive) {
      found.add(ContentLabel.SEXUAL_SUGGESTIVE);
    }
    boolean minor = MINOR_TERM.matcher(folded).find() || MINOR_AGE.matcher(folded).find();
    if (MINOR_STANDALONE.matcher(folded).find() || (minor && (explicit || suggestive))) {
      found.add(ContentLabel.MINORS_RISK);
    }
    if (VIOLENCE.matcher(folded).find()) {
      found.add(ContentLabel.VIOLENCE_GRAPHIC);
    }
    if (REAL_PERSON.matcher(folded).find()) {
      found.add(ContentLabel.REAL_PERSON_LIKENESS);
    }
    operatorRules.forEach(
        (label, patterns) -> {
          if (patterns.stream().anyMatch(p -> p.matcher(folded).find())) {
            found.add(label);
          }
        });
    if (found.isEmpty()) {
      found.add(ContentLabel.NONE);
    }
    return found;
  }

  /** Lower-cased, accents stripped: an accent is not a way through. */
  static String fold(String value) {
    if (value == null) {
      return "";
    }
    return MARKS
        .matcher(Normalizer.normalize(value, Normalizer.Form.NFD))
        .replaceAll("")
        .toLowerCase(Locale.ROOT);
  }
}
