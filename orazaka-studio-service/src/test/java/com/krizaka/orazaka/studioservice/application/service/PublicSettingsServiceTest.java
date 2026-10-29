package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The few settings the public site may read without a token (orazaka-web-client#353): an
 * allow-list, fail closed, and nothing else of the runtime config ever leaves through it.
 */
class PublicSettingsServiceTest {

  private final StudioRuntimeConfigService runtimeConfig = mock(StudioRuntimeConfigService.class);
  private final PublicSettingsService publicSettings = new PublicSettingsService(runtimeConfig);

  @Test
  @DisplayName("the allow-list holds exactly the Mac download switch")
  void allowListIsTheMacSwitch() {
    assertThat(PublicSettingsService.PUBLIC_KEYS).containsExactly("downloads.mac-enabled");
  }

  @Test
  @DisplayName("the Mac switch is what the admin set")
  void readsWhatTheAdminSet() {
    when(runtimeConfig.booleanValue("downloads.mac-enabled", false)).thenReturn(true);

    assertThat(publicSettings.downloads().macEnabled()).isTrue();
  }

  @Test
  @DisplayName("an absent or unreadable row means off: the read defaults to false")
  void failsClosed() {
    when(runtimeConfig.booleanValue("downloads.mac-enabled", false)).thenReturn(false);

    assertThat(publicSettings.downloads().macEnabled()).isFalse();
    verify(runtimeConfig).booleanValue("downloads.mac-enabled", false);
  }

  @Test
  @DisplayName("a key outside the allow-list is refused, whatever the caller asks")
  void refusesAKeyOutsideTheList() {
    assertThatThrownBy(() -> publicSettings.requirePublic("run.fan-out-max"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> publicSettings.requirePublic(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
