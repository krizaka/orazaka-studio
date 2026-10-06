package com.orazaka.studioservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orazaka.studioservice.domain.exception.PackInstallException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * The install-time half of ADR-072: a rule that cannot fail stops the bundle.
 *
 * <p>The pack's own Python suite enforces the same property, which covers packs in this repository
 * and is only discipline for anyone else. These cases are the plant that proves the refusal is real
 * — a rule matching everything, and a rule matching nothing, each built here rather than described.
 */
class RulesetFixtureValidatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("a pack shipping no ruleset is not judged")
  void aPackWithoutRulesetsPasses(@TempDir Path bundle) {
    assertThatCode(() -> RulesetFixtureValidator.verify(bundle, MAPPER)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a rule that distinguishes its own pair installs")
  void aDiscriminatingRuleInstalls(@TempDir Path bundle) throws IOException {
    writeRuleset(
        bundle,
        """
        {"rules":[{"ruleId":"OK-001","method":"DETERMINISTIC",
          "check":{"type":"REQUIRED_PATTERN","pattern":"(?i)\\\\bdemeurant\\\\b"},
          "fixtures":{"pass":"M. Morel, demeurant 8 rue Vauban.","fail":"Le bailleur et le locataire."}}]}
        """);

    assertThatCode(() -> RulesetFixtureValidator.verify(bundle, MAPPER)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a rule that always passes is refused — FR-LEASE-001's defect, planted")
  void aRuleThatCannotFailIsRefused(@TempDir Path bundle) throws IOException {
    // The word "bailleur" appears in every lease, for a requirement that is the identity of the
    // parties. The negative fixture is a document naming nobody, and the rule passes it.
    writeRuleset(
        bundle,
        """
        {"rules":[{"ruleId":"PLANT-ALWAYS-PASSES","method":"DETERMINISTIC",
          "check":{"type":"REQUIRED_PATTERN","pattern":"(?i)\\\\bbailleur\\\\b"},
          "fixtures":{"pass":"M. Morel, demeurant 8 rue Vauban, bailleur.",
                      "fail":"Le bailleur donne à bail le logement désigné."}}]}
        """);

    assertThatThrownBy(() -> RulesetFixtureValidator.verify(bundle, MAPPER))
        .isInstanceOf(PackInstallException.class)
        .hasMessageContaining("PLANT-ALWAYS-PASSES")
        .hasMessageContaining("expected FAIL and returned PASS")
        .hasMessageContaining("cannot fail");
  }

  @Test
  @DisplayName("a rule that always fails is refused too — FR-LEASE-003's defect")
  void aRuleThatCannotPassIsRefused(@TempDir Path bundle) throws IOException {
    writeRuleset(
        bundle,
        """
        {"rules":[{"ruleId":"PLANT-ALWAYS-FAILS","method":"DETERMINISTIC",
          "check":{"type":"REQUIRED_PATTERN","pattern":"(?i)dur[ée]e\\\\s+du\\\\s+bail"},
          "fixtures":{"pass":"Consenti pour une durée de trois ans.","fail":"Prend effet le 1er octobre."}}]}
        """);

    assertThatThrownBy(() -> RulesetFixtureValidator.verify(bundle, MAPPER))
        .isInstanceOf(PackInstallException.class)
        .hasMessageContaining("expected PASS and returned FAIL");
  }

  @Test
  @DisplayName("a deterministic rule shipping no fixtures is refused")
  void aRuleWithoutFixturesIsRefused(@TempDir Path bundle) throws IOException {
    writeRuleset(
        bundle,
        """
        {"rules":[{"ruleId":"NO-FIXTURES","method":"DETERMINISTIC",
          "check":{"type":"REQUIRED_PATTERN","pattern":"x"}}]}
        """);

    assertThatThrownBy(() -> RulesetFixtureValidator.verify(bundle, MAPPER))
        .isInstanceOf(PackInstallException.class)
        .hasMessageContaining("NO-FIXTURES ships no fixture pair");
  }

  @Test
  @DisplayName("a JUDGMENT rule is exempt — its verdict comes from a model")
  void aJudgmentRuleIsExempt(@TempDir Path bundle) throws IOException {
    writeRuleset(
        bundle,
        """
        {"rules":[{"ruleId":"JUDGED-001","method":"JUDGMENT","question":"Is the penalty balanced?"}]}
        """);

    assertThatCode(() -> RulesetFixtureValidator.verify(bundle, MAPPER)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("only the newest version of a ruleset is judged — a replay must stay identical")
  void supersededVersionsAreExempt(@TempDir Path bundle) throws IOException {
    Path directory = Files.createDirectories(bundle.resolve("rulesets").resolve("fr-lease"));
    // Frozen, and broken: it always passes. Correcting it would change a verdict already delivered.
    Files.writeString(
        directory.resolve("2024-07-01.json"),
        """
        {"rules":[{"ruleId":"OLD-001","method":"DETERMINISTIC",
          "check":{"type":"REQUIRED_PATTERN","pattern":"(?i)e"},
          "fixtures":{"pass":"le bailleur","fail":"le locataire"}}]}
        """);
    Files.writeString(
        directory.resolve("2026-07-01.json"),
        """
        {"rules":[{"ruleId":"NEW-001","method":"DETERMINISTIC",
          "check":{"type":"REQUIRED_PATTERN","pattern":"(?i)\\\\bdemeurant\\\\b"},
          "fixtures":{"pass":"demeurant 8 rue Vauban","fail":"le bailleur"}}]}
        """);

    assertThatCode(() -> RulesetFixtureValidator.verify(bundle, MAPPER)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("every phrasing a rule claims is checked, not just the first")
  void everyDeclaredPhrasingIsChecked(@TempDir Path bundle) throws IOException {
    writeRuleset(
        bundle,
        """
        {"rules":[{"ruleId":"MULTI-001","method":"DETERMINISTIC",
          "check":{"type":"FORBIDDEN_PATTERN","pattern":"(?i)interdit[^.]{0,40}recevoir"},
          "fixtures":{"pass":["Le locataire peut recevoir qui bon lui semble."],
                      "fail":["Il est interdit au locataire de recevoir des visites.",
                              "Le preneur ne pourra recevoir aucun invité."]}}]}
        """);

    assertThatThrownBy(() -> RulesetFixtureValidator.verify(bundle, MAPPER))
        .isInstanceOf(PackInstallException.class)
        .hasMessageContaining("ne pourra recevoir aucun invité");
  }

  @Test
  @DisplayName("the refusal names every rule, so one install says all of it")
  void theRefusalNamesEveryFailingRule(@TempDir Path bundle) throws IOException {
    writeRuleset(
        bundle,
        """
        {"rules":[
          {"ruleId":"BAD-A","method":"DETERMINISTIC","check":{"type":"REQUIRED_PATTERN","pattern":"(?i)e"},
           "fixtures":{"pass":"le bailleur","fail":"le locataire"}},
          {"ruleId":"BAD-B","method":"DETERMINISTIC","check":{"type":"REQUIRED_PATTERN","pattern":"zzz"},
           "fixtures":{"pass":"le bailleur","fail":"le locataire"}}]}
        """);

    assertThatThrownBy(() -> RulesetFixtureValidator.verify(bundle, MAPPER))
        .isInstanceOf(PackInstallException.class)
        .satisfies(refusal -> assertThat(refusal.getMessage()).contains("BAD-A").contains("BAD-B"));
  }

  private static void writeRuleset(Path bundle, String json) throws IOException {
    Path directory = Files.createDirectories(bundle.resolve("rulesets").resolve("fr-lease"));
    Files.writeString(directory.resolve("2026-07-01.json"), json);
  }
}
