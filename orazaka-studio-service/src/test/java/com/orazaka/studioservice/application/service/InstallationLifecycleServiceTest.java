package com.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InstallationLifecycleServiceTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final InstallationLifecycleService lifecycleService =
      new InstallationLifecycleService(jdbcTemplate);

  @Test
  @DisplayName("A lapse pauses only ACTIVE installations — never a revoked one")
  void pauseTargetsActiveOnly() {
    when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString())).thenReturn(2);

    assertThat(lifecycleService.pauseFor(ACTOR)).isEqualTo(2);
    // Reviving something the actor deliberately uninstalled would be worse than doing nothing.
    verify(jdbcTemplate).update(anyString(), eqPaused(), eqActor(), eqActive());
  }

  @Test
  @DisplayName(
      "Recovering resumes what the lapse paused, so nobody reinstalls to get their work back")
  void resumeRevivesPaused() {
    when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

    assertThat(lifecycleService.resumeFor(ACTOR)).isEqualTo(1);
    verify(jdbcTemplate).update(anyString(), eqActive(), eqActor(), eqPaused());
  }

  @Test
  @DisplayName("Nothing to pause reports zero rather than pretending it acted")
  void pauseIsHonestWhenNothingChanged() {
    when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString())).thenReturn(0);

    assertThat(lifecycleService.pauseFor(ACTOR)).isZero();
  }

  private static String eqActive() {
    return org.mockito.ArgumentMatchers.eq("ACTIVE");
  }

  private static String eqPaused() {
    return org.mockito.ArgumentMatchers.eq("PAUSED");
  }

  private static String eqActor() {
    return org.mockito.ArgumentMatchers.eq(ACTOR);
  }
}
