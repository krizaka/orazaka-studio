package com.krizaka.orazaka.studioservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.studioservice.domain.model.ContentLabel;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RulesContentClassifierTest {

  private final RulesContentClassifier classifier = new RulesContentClassifier();

  static Stream<ContentFixtures.Fixture> fixtures() {
    return ContentFixtures.all().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  @DisplayName("Each fixture is labelled as expected")
  void labelsFixtures(ContentFixtures.Fixture fixture) {
    assertThat(classifier.classify(fixture.prompt(), Map.of())).isEqualTo(fixture.expected());
  }

  @Test
  @DisplayName("The operator's own adult prompt (local file, never committed) is labelled explicit")
  void localAdultPromptIsExplicit() {
    String prompt = ContentFixtures.localAdultPrompt().orElse(null);
    Assumptions.assumeTrue(prompt != null, "prompt-adult.md is not on this machine");
    // Only the labels are asserted, and nothing of the text is printed.
    assertThat(classifier.classify(prompt, Map.of())).contains(ContentLabel.SEXUAL_EXPLICIT);
  }

  @Test
  @DisplayName("Accents and case are not a way through")
  void foldsAccentsAndCase() {
    assertThat(classifier.classify("PORNOGRAPHIQUE", Map.of()))
        .contains(ContentLabel.SEXUAL_EXPLICIT);
    assertThat(classifier.classify("érotique", Map.of())).contains(ContentLabel.SEXUAL_SUGGESTIVE);
  }

  @Test
  @DisplayName("An operator rule adds a label")
  void operatorRule() {
    var rules = Map.of(ContentLabel.VIOLENCE_GRAPHIC, List.of(Pattern.compile("\\bsplatter\\b")));
    assertThat(classifier.classify("a splatter film", rules))
        .contains(ContentLabel.VIOLENCE_GRAPHIC);
  }

  @Test
  @DisplayName("A minor's age with an adult label is a minors risk; an adult's age is not")
  void minorAges() {
    assertThat(classifier.classify("a 15 yo in lingerie", Map.of()))
        .contains(ContentLabel.MINORS_RISK);
    assertThat(classifier.classify("a 25 year old in lingerie", Map.of()))
        .doesNotContain(ContentLabel.MINORS_RISK);
    assertThat(classifier.classify("a child playing in a park", Map.of()))
        .containsExactly(ContentLabel.NONE);
  }
}
