package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.model.EntitlementSnapshot;
import com.krizaka.billing.domain.port.EntitlementProvider;
import com.krizaka.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import com.krizaka.orazaka.studio.domain.model.StudioStatus;
import com.krizaka.orazaka.studioservice.domain.port.EntitlementGrantRepository;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.RestErrorResolver;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The gate of the Contributor pack's Studios (ADR-093 §3, krizaka/orazaka#450): the Mac app shows
 * the Code tab from the catalogue's answer, but the model turn of every run is refused by the
 * engine itself, and no local flag unlocks it.
 */
class CodeContributorGateTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final String PACK = "code-contributor";
  private static final String[] STUDIOS = {"code-issue-to-pr", "code-read-write"};

  private final EntitlementProvider entitlements = mock(EntitlementProvider.class);
  private final EntitlementGrantRepository operatorGrants = mock(EntitlementGrantRepository.class);
  private final StudioAccessService access = new StudioAccessService(entitlements, operatorGrants);

  private static Studio studio(String key) {
    return new Studio(
        key,
        "Label",
        null,
        "developer",
        "code",
        null,
        StudioPricing.PAID,
        PACK,
        PackKind.VERTICAL,
        "studio." + key,
        StudioStatus.PUBLISHED,
        "orazaka",
        "1.0.0",
        Set.of(),
        Instant.parse("2026-10-27T10:00:00Z"));
  }

  private void entitled(String... keys) {
    Map<String, String> matrix = new java.util.HashMap<>();
    for (String key : keys) {
      matrix.put(key, "true");
    }
    when(entitlements.forActor(anyString()))
        .thenReturn(
            new EntitlementSnapshot(ACTOR, "premium", matrix, 5000, Instant.now().plusSeconds(60)));
  }

  @Test
  @DisplayName(
      "a forged call without the entitlement is refused, for both Studios, and the wire says studio_not_entitled")
  void aForgedCallIsRefused() {
    entitled();
    for (String key : STUDIOS) {
      assertThatThrownBy(() -> access.requireEntitled(studio(key), ACTOR))
          .isInstanceOf(StudioNotEntitledException.class);
    }
    ResponseEntity<Map<String, Object>> wire =
        new RestErrorResolver()
            .handleNotEntitled(
                new StudioNotEntitledException(
                    "code-issue-to-pr", "studio.code-issue-to-pr", PACK));
    assertThat(wire.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(wire.getBody()).containsEntry("status", "studio_not_entitled");
  }

  @Test
  @DisplayName(
      "one Studio's entitlement does not open the other, and an unrelated entitlement opens neither")
  void eachStudioHasItsOwnKey() {
    entitled("studio.code-read-write", "studio.trade-showcase");
    assertThatCode(() -> access.requireEntitled(studio("code-read-write"), ACTOR))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .isInstanceOf(StudioNotEntitledException.class);
  }

  @Test
  @DisplayName("an operator grant (dev, pilots, dogfooding) opens it; revoking it closes it")
  void anOperatorGrantOpensIt() {
    entitled();
    when(operatorGrants.grantedKeys(ACTOR)).thenReturn(Set.of("studio.code-issue-to-pr"));
    assertThatCode(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .doesNotThrowAnyException();
    when(operatorGrants.grantedKeys(ACTOR)).thenReturn(Set.of());
    assertThatThrownBy(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .isInstanceOf(StudioNotEntitledException.class);
  }

  @Test
  @DisplayName("a billing outage does not open a PAID Studio")
  void anOutageDoesNotOpenIt() {
    when(entitlements.forActor(anyString()))
        .thenReturn(EntitlementSnapshot.unresolved(ACTOR, Instant.now().plusSeconds(5)));
    assertThatThrownBy(() -> access.requireEntitled(studio("code-issue-to-pr"), ACTOR))
        .isInstanceOf(StudioNotEntitledException.class);
  }
}
