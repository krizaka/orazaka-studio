package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studioservice.application.service.PublicSettingsService;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.PublicDownloadsResponse;
import com.krizaka.orazaka.studioservice.infrastructure.config.SecurityConfig;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** The one anonymous door of the Studio service: public settings, read only, cached a minute. */
class PublicSettingsControllerTest {

  private final PublicSettingsService publicSettings = mock(PublicSettingsService.class);
  private final PublicSettingsController controller = new PublicSettingsController(publicSettings);

  @Test
  @DisplayName(
      "the path sits under /api/v1/studios (the edge's prefix) and the security chain opens it")
  void theChainOpensExactlyThisPrefix() {
    String path = PublicSettingsController.class.getAnnotation(RequestMapping.class).value()[0];

    assertThat(path).isEqualTo("/api/v1/studios/public");
    assertThat(SecurityConfig.PUBLIC_PATHS).isEqualTo(path + "/**");
  }

  @Test
  @DisplayName("every handler is a GET: nothing here writes, and nothing needs a role")
  void everyHandlerIsAnAnonymousGet() {
    Method[] handlers =
        Arrays.stream(PublicSettingsController.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .toArray(Method[]::new);
    assertThat(handlers).isNotEmpty();
    for (Method handler : handlers) {
      assertThat(handler.getAnnotation(GetMapping.class)).as(handler.getName()).isNotNull();
      assertThat(handler.getAnnotation(PreAuthorize.class)).as(handler.getName()).isNull();
    }
  }

  @Test
  @DisplayName("the downloads answer carries the switch alone, cached for a minute by anyone")
  void downloadsAreCachedAndCarryNothingElse() {
    when(publicSettings.downloads()).thenReturn(new PublicSettingsService.Downloads(true));

    ResponseEntity<PublicDownloadsResponse> answer = controller.downloads();

    assertThat(answer.getBody()).isEqualTo(new PublicDownloadsResponse(true));
    assertThat(answer.getHeaders().getCacheControl()).isEqualTo("max-age=60, public");
    assertThat(PublicDownloadsResponse.class.getRecordComponents()).hasSize(1);
  }
}
