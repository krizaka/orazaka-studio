package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.jobs.domain.model.CapabilityRoute;
import com.krizaka.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.krizaka.orazaka.studio.domain.exception.BlueprintValidationException;
import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.BlueprintStep;
import com.krizaka.orazaka.studio.domain.model.ErrorPolicy;
import com.krizaka.orazaka.studio.domain.model.StepKind;
import com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Publish-time capability resolution (ADR-037 §4.3): a blueprint naming a capability with no
 * enabled route is refused in the console, in front of the admin who can fix it — not at 2 a.m.
 * inside a saga that already took the actor's credits.
 */
class BlueprintPublishServiceTest {

  private static final String STUDIO = "a-studio";
  private static final String VERSION = "1.0.0";

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final BlueprintRepository blueprintRepository = mock(BlueprintRepository.class);
  private final StudioCatalogService catalogService = mock(StudioCatalogService.class);

  private final Map<String, CapabilityRoute> registry = new HashMap<>();
  private final CapabilityRoutingClient routingClient =
      featureKey -> Optional.ofNullable(registry.get(featureKey));

  private final BlueprintPublishService service =
      new BlueprintPublishService(jdbcTemplate, blueprintRepository, catalogService, routingClient);

  private void route(String featureKey) {
    registry.put(
        featureKey,
        new CapabilityRoute(featureKey, "job.media.generate", null, "IMAGE", "BATCH", true));
  }

  private static BlueprintStep step(String id, StepKind kind, String featureKey) {
    return new BlueprintStep(
        id,
        kind,
        featureKey,
        null,
        Set.of(),
        Map.of(),
        id,
        null,
        0,
        ErrorPolicy.FAIL,
        Map.of(),
        1,
        Duration.ofMinutes(1),
        null);
  }

  private void blueprintOf(BlueprintStep... steps) {
    when(blueprintRepository.find(STUDIO, VERSION))
        .thenReturn(
            Optional.of(
                new Blueprint(
                    STUDIO,
                    VERSION,
                    BlueprintStatus.DRAFT,
                    "{}",
                    List.of(steps),
                    List.of(),
                    Map.of(),
                    10L,
                    "first",
                    null)));
  }

  @Test
  @DisplayName("A blueprint whose every capability resolves publishes")
  void publishesWhenEveryCapabilityResolves() {
    route("orazaka.core.media.vision");
    blueprintOf(step("describe", StepKind.CAPABILITY, "orazaka.core.media.vision"));
    when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThatCode(() -> service.publish(STUDIO, VERSION)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("A capability with no enabled route refuses the publish, naming step AND capability")
  void refusesAnUnroutableCapability() {
    blueprintOf(step("validate", StepKind.CAPABILITY, "orazaka.doc.validate"));

    assertThatThrownBy(() -> service.publish(STUDIO, VERSION))
        .isInstanceOf(BlueprintValidationException.class)
        .hasMessageContaining("validate")
        .hasMessageContaining("orazaka.doc.validate");

    // Nothing was written: a refused publish must not move latest_version, or every installation
    // would pin a version that cannot run.
    assertThat(mockingDetails(jdbcTemplate).getInvocations()).isEmpty();
  }

  @Test
  @DisplayName("Every unroutable step is reported at once, not one publish attempt at a time")
  void reportsEveryUnroutableStep() {
    route("orazaka.core.media.vision");
    blueprintOf(
        step("describe", StepKind.CAPABILITY, "orazaka.core.media.vision"),
        step("extract", StepKind.CAPABILITY, "orazaka.doc.extract"),
        step("verify", StepKind.CAPABILITY, "orazaka.doc.validate"));

    assertThatThrownBy(() -> service.publish(STUDIO, VERSION))
        .isInstanceOf(BlueprintValidationException.class)
        .hasMessageContaining("orazaka.doc.extract")
        .hasMessageContaining("orazaka.doc.validate");
  }

  @Test
  @DisplayName("Non-CAPABILITY steps are not routed — an APPROVAL step has no queue by design")
  void ignoresStepsThatAreNotCapabilities() {
    blueprintOf(step("sign-off", StepKind.APPROVAL, null), step("shape", StepKind.TRANSFORM, null));
    when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThatCode(() -> service.publish(STUDIO, VERSION)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("A version that does not exist is still refused before anything is read")
  void refusesAnAbsentVersion() {
    when(blueprintRepository.find(STUDIO, VERSION)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.publish(STUDIO, VERSION))
        .isInstanceOf(BlueprintValidationException.class)
        .hasMessageContaining(VERSION);
  }
}
