package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintStep;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studioservice.domain.exception.ContentPolicyRefusedException;
import com.krizaka.orazaka.studioservice.domain.model.BlueprintVersion;
import com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import com.krizaka.orazaka.studioservice.infrastructure.support.ContentFixtures;
import com.krizaka.orazaka.studioservice.infrastructure.support.RulesContentClassifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * The refusal happens before anything that could reach a provider. "A provider" here is everything
 * after the policy in {@code launch}: the credit hold, the run row, and the saga that dispatches
 * steps to the job plane — all mocks that must record zero calls for a refused prompt.
 */
class StudioRunContentPolicyTest {

  private static final String VIDEO = "orazaka.core.media.video";

  private final CreditReservationService credits = mock(CreditReservationService.class);
  private final RunSagaService saga = mock(RunSagaService.class);
  private final OutboxService outbox = mock(OutboxService.class);
  private final FakePolicyJdbc jdbc = new FakePolicyJdbc();
  private StudioRunService runs;

  @BeforeEach
  void wire() {
    StudioCatalogService catalog = mock(StudioCatalogService.class);
    StudioAccessService access = mock(StudioAccessService.class);
    BlueprintRepository blueprints = mock(BlueprintRepository.class);
    Studio studio = mock(Studio.class);
    when(studio.kind()).thenReturn(PackKind.TOOLKIT);
    when(catalog.find(anyString(), any())).thenReturn(Optional.of(studio));
    when(access.isIncluded(any(), anyString())).thenReturn(true);
    BlueprintVersion version = mock(BlueprintVersion.class);
    when(version.version()).thenReturn("1.0.0");
    when(catalog.latestPublished(anyString())).thenReturn(Optional.of(version));
    BlueprintStep step = mock(BlueprintStep.class);
    when(step.featureKey()).thenReturn(VIDEO);
    Blueprint blueprint = mock(Blueprint.class);
    when(blueprint.inputSchema()).thenReturn("{}");
    when(blueprint.steps()).thenReturn(List.of(step));
    when(blueprint.estimatedCredits()).thenReturn(5L);
    when(blueprints.find(anyString(), anyString())).thenReturn(Optional.of(blueprint));
    when(credits.hold(anyString(), anyString(), anyLong())).thenReturn("hold-1");

    StudioRuntimeConfigService config = mock(StudioRuntimeConfigService.class);
    when(config.intValue(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(2);
    ContentPolicyService policy =
        new ContentPolicyService(
            jdbc,
            new RulesContentClassifier(),
            new AbstractPlatformTransactionManager() {
              @Override
              protected Object doGetTransaction() {
                return new Object();
              }

              @Override
              protected void doBegin(
                  Object t, org.springframework.transaction.TransactionDefinition d) {}

              @Override
              protected void doCommit(DefaultTransactionStatus s) {}

              @Override
              protected void doRollback(DefaultTransactionStatus s) {}
            });
    runs =
        new StudioRunService(
            jdbc,
            mock(StudioInstallationService.class),
            blueprints,
            credits,
            mock(RunSettlementService.class),
            saga,
            config,
            outbox,
            new ObjectMapper(),
            new ColumnValueResolver(new ObjectMapper()),
            mock(RunAuditService.class),
            catalog,
            access,
            policy);
  }

  private static Map<String, Object> inputs(String fixture) {
    String text =
        ContentFixtures.all().stream()
            .filter(f -> f.id().equals(fixture))
            .findFirst()
            .orElseThrow()
            .prompt();
    return Map.of("prompt", text);
  }

  @Test
  @DisplayName("minors_risk: refused, and no hold, no run, no dispatch")
  void minorsRefusedBeforeAnyProvider() {
    assertThatThrownBy(() -> runs.start("video-studio", "actor", inputs("minor-risk")))
        .isInstanceOf(ContentPolicyRefusedException.class);
    verifyNoInteractions(saga, outbox);
    verify(credits, never()).hold(anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName(
      "provider marked not allowing adult content: explicit prompt refused, nothing reached")
  void providerTermsRefuseBeforeAnyProvider() {
    jdbc.provider(VIDEO, false, "BLOCK");
    assertThatThrownBy(() -> runs.start("video-studio", "actor", inputs("explicit-redacted")))
        .isInstanceOf(ContentPolicyRefusedException.class)
        .hasMessageContaining("content.policy_refused");
    verifyNoInteractions(saga, outbox);
    verify(credits, never()).hold(anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName("tenant refusal: refused, nothing reached")
  void tenantRefusal() {
    jdbc.tenant("*", "SEXUAL_EXPLICIT", "REFUSE");
    assertThatThrownBy(() -> runs.start("video-studio", "actor", inputs("explicit-redacted")))
        .isInstanceOf(ContentPolicyRefusedException.class);
    verifyNoInteractions(saga);
  }

  @Test
  @DisplayName("the dry run answers the decision and touches nothing")
  void dryRunTouchesNothing() {
    jdbc.provider(VIDEO, false, "BLOCK");
    var decision = runs.checkPolicy("video-studio", "actor", inputs("explicit-redacted"));
    assertThat(decision.refused()).isTrue();
    assertThat(decision.providers()).containsExactly(VIDEO);
    verifyNoInteractions(saga, outbox);
    verify(credits, never()).hold(anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName(
      "permissive default: an explicit prompt is not refused by the policy (the operator decides)")
  void permissiveDefaultReachesTheHold() {
    // The policy lets it through; the rest of launch needs a database, so stop at the hold.
    when(credits.hold(anyString(), anyString(), anyLong()))
        .thenThrow(new IllegalStateException("past the policy"));
    assertThatThrownBy(() -> runs.start("video-studio", "actor", inputs("explicit-redacted")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("past the policy");
    verify(credits).hold(anyString(), anyString(), anyLong());
  }
}
