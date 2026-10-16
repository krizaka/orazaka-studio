package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.studioservice.domain.exception.ContentPolicyRefusedException;
import com.krizaka.orazaka.studioservice.domain.model.ContentDecision;
import com.krizaka.orazaka.studioservice.infrastructure.support.ContentFixtures;
import com.krizaka.orazaka.studioservice.infrastructure.support.RulesContentClassifier;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class ContentPolicyServiceTest {

  private static final String VIDEO = "orazaka.core.media.video";
  private static final String ACTOR = "actor-1";

  /** No transaction machinery needed: the fake JDBC has no connection. */
  private static final class NoopTx extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) {}

    @Override
    protected void doCommit(DefaultTransactionStatus s) {}

    @Override
    protected void doRollback(DefaultTransactionStatus s) {}
  }

  private static ContentPolicyService service(FakePolicyJdbc jdbc) {
    return new ContentPolicyService(jdbc, new RulesContentClassifier(), new NoopTx());
  }

  private static String prompt(String id) {
    return ContentFixtures.all().stream()
        .filter(f -> f.id().equals(id))
        .findFirst()
        .orElseThrow()
        .prompt();
  }

  /** One row of the decision table. */
  private record Case(
      String name, String fixture, Consumer<FakePolicyJdbc> config, boolean refused, String rule) {}

  private static List<Case> table() {
    return List.of(
        new Case("benign, nothing configured", "benign", j -> {}, false, "permissive.default"),
        new Case(
            "explicit, permissive default (self-hosted)",
            "explicit-redacted",
            j -> {},
            false,
            "permissive.default"),
        new Case(
            "suggestive, permissive default", "suggestive", j -> {}, false, "permissive.default"),
        new Case("violent, permissive default", "violent", j -> {}, false, "permissive.default"),
        new Case(
            "explicit, tenant refuses",
            "explicit-redacted",
            j -> j.tenant("*", "SEXUAL_EXPLICIT", "REFUSE"),
            true,
            "tenant.refuse.SEXUAL_EXPLICIT"),
        new Case(
            "explicit, this actor refuses only",
            "explicit-redacted",
            j -> j.tenant(ACTOR, "SEXUAL_EXPLICIT", "REFUSE"),
            true,
            "tenant.refuse.SEXUAL_EXPLICIT"),
        new Case(
            "explicit, another actor's refusal does not apply",
            "explicit-redacted",
            j -> j.tenant("someone-else", "SEXUAL_EXPLICIT", "REFUSE"),
            false,
            "permissive.default"),
        new Case(
            "explicit, provider marked not allowing adult (BLOCK)",
            "explicit-redacted",
            j -> j.provider(VIDEO, false, "BLOCK"),
            true,
            "provider." + VIDEO + ".allows_adult_content"),
        new Case(
            "suggestive, provider marked not allowing adult (BLOCK)",
            "suggestive",
            j -> j.provider(VIDEO, false, "BLOCK"),
            true,
            "provider." + VIDEO + ".allows_adult_content"),
        new Case(
            "explicit, provider WARN only",
            "explicit-redacted",
            j -> j.provider(VIDEO, false, "WARN"),
            false,
            "permissive.default"),
        new Case(
            "benign, provider not allowing adult",
            "benign",
            j -> j.provider(VIDEO, false, "BLOCK"),
            false,
            "permissive.default"),
        new Case(
            "real person, tenant refuses",
            "real-person",
            j -> j.tenant("*", "REAL_PERSON_LIKENESS", "REFUSE"),
            true,
            "tenant.refuse.REAL_PERSON_LIKENESS"),
        new Case(
            "minors risk, nothing configured", "minor-risk", j -> {}, true, "minors_risk.always"),
        new Case(
            "minors risk, tenant explicitly ALLOWs adult content",
            "minor-risk",
            j -> j.tenant("*", "SEXUAL_EXPLICIT", "ALLOW"),
            true,
            "minors_risk.always"),
        new Case(
            "minors risk, provider allows everything",
            "minor-risk",
            j -> j.provider(VIDEO, true, "BLOCK"),
            true,
            "minors_risk.always"),
        new Case(
            "minors risk standalone term",
            "minor-standalone",
            j -> {},
            true,
            "minors_risk.always"));
  }

  @Test
  @DisplayName("The decision table: operator configuration decides, minors_risk never bends")
  void decisionTable() {
    for (Case c : table()) {
      FakePolicyJdbc jdbc = new FakePolicyJdbc();
      c.config().accept(jdbc);
      ContentDecision d =
          service(jdbc).evaluate(ACTOR, Map.of("prompt", prompt(c.fixture())), List.of(VIDEO));
      assertThat(d.refused()).as(c.name()).isEqualTo(c.refused());
      assertThat(d.rule()).as(c.name()).isEqualTo(c.rule());
    }
  }

  @Test
  @DisplayName("A WARN provider lets the run through and says why")
  void warnIsReported() {
    FakePolicyJdbc jdbc = new FakePolicyJdbc().provider(VIDEO, false, "WARN");
    ContentDecision d =
        service(jdbc)
            .evaluate(ACTOR, Map.of("prompt", prompt("explicit-redacted")), List.of(VIDEO));
    assertThat(d.refused()).isFalse();
    assertThat(d.warnings()).containsExactly("provider." + VIDEO + ".allows_adult_content");
    assertThat(d.verb()).isEqualTo("WARNED");
  }

  @Test
  @DisplayName("Nested inputs are classified too")
  void nestedInputs() {
    ContentDecision d =
        service(new FakePolicyJdbc())
            .evaluate(
                ACTOR, Map.of("scenes", List.of(Map.of("text", prompt("minor-risk")))), List.of());
    assertThat(d.refused()).isTrue();
  }

  @Test
  @DisplayName("enforce throws content.policy_refused and records label + rule, never the prompt")
  void enforceRecordsAndThrows() {
    FakePolicyJdbc jdbc = new FakePolicyJdbc();
    String text = prompt("minor-risk");
    assertThatThrownBy(
            () -> service(jdbc).enforce(ACTOR, "s", Map.of("prompt", text), List.of(VIDEO)))
        .isInstanceOf(ContentPolicyRefusedException.class)
        .hasMessageContaining(ContentDecision.REFUSED_CODE);
    assertThat(jdbc.audit).hasSize(1);
    Object[] row = jdbc.audit.get(0);
    assertThat(row[3]).isEqualTo("REFUSED");
    assertThat(row[4]).isEqualTo("minors_risk.always");
    assertThat(java.util.Arrays.toString(row)).doesNotContain(text);
  }

  @Test
  @DisplayName("Benign prompts leave no audit row")
  void benignNotAudited() {
    FakePolicyJdbc jdbc = new FakePolicyJdbc();
    service(jdbc).enforce(ACTOR, "s", Map.of("prompt", prompt("benign")), List.of(VIDEO));
    assertThat(jdbc.audit).isEmpty();
  }

  @Test
  @DisplayName("The dry run decides and records as a dry run, and never throws")
  void dryRun() {
    FakePolicyJdbc jdbc = new FakePolicyJdbc().tenant("*", "SEXUAL_EXPLICIT", "REFUSE");
    ContentDecision d =
        service(jdbc)
            .check(ACTOR, "s", Map.of("prompt", prompt("explicit-redacted")), List.of(VIDEO));
    assertThat(d.refused()).isTrue();
    assertThat(jdbc.audit.get(0)[6]).isEqualTo(true);
  }
}
