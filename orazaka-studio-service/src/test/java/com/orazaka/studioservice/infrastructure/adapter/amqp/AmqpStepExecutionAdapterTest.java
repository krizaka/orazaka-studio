package com.orazaka.studioservice.infrastructure.adapter.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import com.orazaka.jobs.domain.exception.UnroutableCapabilityException;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studio.domain.model.PackScopeGuard;
import com.orazaka.studio.domain.model.StepDispatch;
import com.orazaka.studioservice.application.service.OutboxService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AmqpStepExecutionAdapterTest {

  private static final UUID RUN = UUID.randomUUID();

  private final OutboxService outboxService = mock(OutboxService.class);

  /**
   * A stand-in registry, not a mock chain: these tests assert that the adapter publishes the key
   * the ROW carries, so the row has to be the fixture. Mocking {@code route(...)} per test would
   * let a regression that ignores the row still pass.
   */
  private final Map<String, CapabilityRoute> registry = new HashMap<>();

  private final CapabilityRoutingClient routingClient =
      featureKey -> Optional.ofNullable(registry.get(featureKey));

  private final AmqpStepExecutionAdapter adapter =
      new AmqpStepExecutionAdapter(outboxService, routingClient);

  private void route(String featureKey, String routingKey) {
    registry.put(
        featureKey, new CapabilityRoute(featureKey, routingKey, null, "CHAT", "BATCH", true));
  }

  private StepDispatch dispatch(String featureKey) {
    return dispatch(featureKey, Optional.empty());
  }

  private StepDispatch dispatch(String featureKey, Optional<PackScopeGuard> scopeGuard) {
    return new StepDispatch(
        RUN,
        "describe",
        2,
        featureKey,
        null,
        Map.of("assetId", "a1"),
        "actor-1",
        "corr-1",
        Map.of("tone", "premium"),
        scopeGuard,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        com.orazaka.jobs.domain.model.DataClass.SENSITIVE);
  }

  /**
   * The command as it was APPENDED — the adapter publishes nothing itself since ADR-067; the outbox
   * relay does, after the saga's transaction commits.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> capturedCommand(String expectedRoutingKey) {
    ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
    verify(outboxService)
        .appendCommand(
            eq(RUN.toString()),
            eq("orazaka.jobs"),
            eq(expectedRoutingKey),
            anyString(),
            body.capture());
    return body.getValue();
  }

  @Test
  @DisplayName("A step is published as a JobCommand carrying runId and stepId for correlation")
  void publishesJobCommandWithCorrelation() {
    route("orazaka.core.media.vision", "job.media.generate");

    String jobId = adapter.submit(dispatch("orazaka.core.media.vision"));

    Map<String, Object> command = capturedCommand("job.media.generate");
    assertThat(command).containsEntry("jobId", jobId).containsEntry("userId", "actor-1");
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) command.get("payload");
    assertThat(payload)
        .containsEntry("runId", RUN.toString())
        .containsEntry("stepId", "describe")
        .containsEntry("ordinal", 2)
        .containsEntry("assetId", "a1");
  }

  @Test
  @DisplayName("[ADR-065] the run's data class travels on the command, not in its payload")
  void theDataClassTravelsWithTheJob() {
    route("orazaka.core.media.vision", "job.media.generate");

    adapter.submit(dispatch("orazaka.core.media.vision"));

    Map<String, Object> command = capturedCommand("job.media.generate");
    assertThat(command).containsEntry("dataClass", "SENSITIVE");
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) command.get("payload");
    assertThat(payload.keySet()).noneMatch(key -> key.toLowerCase().contains("class"));
  }

  @Test
  @DisplayName("[ADR-041] A step NEVER carries a hold — only the run's saga may settle the run")
  void stepNeverCarriesAHold() {
    // The regression this pins: while a dispatch carried the run's hold, JobSettlementListener
    // billed the first step to finish against the run's whole reservation at the AGENT/CALL rate
    // — a constant 1, five credits — and closed it, so every later step ran free. 480 reserved,
    // 5 debited. A payload with no hold cannot do that.
    route("orazaka.core.media.vision", "job.media.generate");

    adapter.submit(dispatch("orazaka.core.media.vision"));

    @SuppressWarnings("unchecked")
    Map<String, Object> payload =
        (Map<String, Object>) capturedCommand("job.media.generate").get("payload");
    assertThat(payload).doesNotContainKey("holdId");
  }

  @Test
  @DisplayName(
      "The routing key is the one the capability's row carries, whatever the key looks like")
  void publishesTheRoutingKeyTheRowCarries() {
    // Deliberately a key no substring heuristic could have routed here: the row decides, and
    // nothing reads the feature key's spelling any more (ADR-037).
    route("orazaka.doc.validate", "job.doc.validate");

    adapter.submit(dispatch("orazaka.doc.validate"));

    capturedCommand("job.doc.validate");
  }

  @Test
  @DisplayName("Each submission gets its own job id")
  void eachSubmissionIsDistinct() {
    route("orazaka.core.media.vision", "job.media.generate");

    String first = adapter.submit(dispatch("orazaka.core.media.vision"));
    String second = adapter.submit(dispatch("orazaka.core.media.vision"));

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("An unroutable capability throws and publishes NOTHING — there is no default queue")
  void unroutableCapabilityIsRefusedAndNothingIsPublished() {
    assertThatThrownBy(() -> adapter.submit(dispatch("orazaka.doc.validate")))
        .isInstanceOf(UnroutableCapabilityException.class)
        .hasMessageContaining("orazaka.doc.validate")
        .hasMessageContaining("describe");

    // The assertion that matters: the old heuristic would have sent this to job.text.process,
    // where it would have failed as a text job — or been answered by an LLM.
    assertThat(mockingDetails(outboxService).getInvocations()).isEmpty();
  }

  @Test
  @DisplayName("A disabled capability is unroutable — the registry never returns a disabled route")
  void aDisabledCapabilityIsUnroutable() {
    // The read side filters disabled rows out, so the adapter sees exactly what an absent row
    // looks like. Asserted here so the adapter is never "fixed" to inspect enabled() itself.
    assertThatThrownBy(() -> adapter.submit(dispatch("orazaka.core.media.vision")))
        .isInstanceOf(UnroutableCapabilityException.class);
  }

  @Test
  @DisplayName("The brand kit travels under orazaka.studio.brand.* for the enrichment interceptor")
  void brandKitTravelsNamespaced() {
    route("orazaka.core.media.vision", "job.media.generate");

    adapter.submit(dispatch("orazaka.core.media.vision"));

    @SuppressWarnings("unchecked")
    Map<String, Object> payload =
        (Map<String, Object>) capturedCommand("job.media.generate").get("payload");
    assertThat(payload).containsEntry("orazaka.studio.brand.tone", "premium");
  }

  @Test
  @DisplayName("[ADR-051] a declared scope guard travels in the payload, terms and refusal both")
  void declaresTheScopeGuardItWasGiven() {
    route("orazaka.core.chat.completion", "job.text.process");

    adapter.submit(
        dispatch(
            "orazaka.core.chat.completion",
            Optional.of(
                new PackScopeGuard(
                    List.of("conseil juridique", "avocat"),
                    "Je rédige des documents, je ne donne pas de conseil juridique."))));

    @SuppressWarnings("unchecked")
    Map<String, Object> payload =
        (Map<String, Object>) capturedCommand("job.text.process").get("payload");
    assertThat(payload.get(JobCommand.SCOPE_REFUSED_TERMS_KEY))
        .isEqualTo("conseil juridique,avocat");
    assertThat(payload.get(JobCommand.SCOPE_REFUSAL_KEY))
        .isEqualTo("Je rédige des documents, je ne donne pas de conseil juridique.");
  }

  @Test
  @DisplayName("[ADR-051] a pack declaring no domain sends no declaration at all")
  void sendsNoScopeDeclarationWhenNoneWasDeclared() {
    route("orazaka.core.chat.completion", "job.text.process");

    adapter.submit(dispatch("orazaka.core.chat.completion"));

    @SuppressWarnings("unchecked")
    Map<String, Object> payload =
        (Map<String, Object>) capturedCommand("job.text.process").get("payload");
    assertThat(payload).doesNotContainKey(JobCommand.SCOPE_REFUSED_TERMS_KEY);
    assertThat(payload).doesNotContainKey(JobCommand.SCOPE_REFUSAL_KEY);
  }
}
