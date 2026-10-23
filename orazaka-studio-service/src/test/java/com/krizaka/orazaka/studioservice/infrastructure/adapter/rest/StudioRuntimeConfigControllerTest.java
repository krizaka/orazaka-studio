package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studio.domain.model.StudioRuntimeConfig;
import com.krizaka.orazaka.studioservice.application.service.StudioRuntimeConfigService;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.RuntimeConfigResponse;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.RuntimeConfigUpdateRequest;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The admin door to the Studio's runtime settings.
 *
 * <p>A plain unit test, like the other controllers here: the role is enforced by method security
 * ({@code SecurityConfig} carries {@code @EnableMethodSecurity}), so what this test pins is that
 * every handler declares it, and that the authenticated name reaches the audit.
 */
class StudioRuntimeConfigControllerTest {

  private static final StudioRuntimeConfig FAN_OUT =
      new StudioRuntimeConfig(
          "run.fan-out-max",
          10,
          "int",
          "Hard cap on forEach expansion.",
          10,
          new StudioRuntimeConfig.Bounds(1L, 1000L, null));

  private final StudioRuntimeConfigService runtimeConfig = mock(StudioRuntimeConfigService.class);
  private final StudioRuntimeConfigController controller =
      new StudioRuntimeConfigController(runtimeConfig);

  @Test
  @DisplayName("every handler is ADMIN only")
  void everyHandlerIsAdminOnly() {
    Method[] handlers =
        Arrays.stream(StudioRuntimeConfigController.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .toArray(Method[]::new);
    assertThat(handlers).isNotEmpty();
    for (Method handler : handlers) {
      PreAuthorize guard = handler.getAnnotation(PreAuthorize.class);
      assertThat(guard).as(handler.getName()).isNotNull();
      assertThat(guard.value()).as(handler.getName()).isEqualTo("hasRole('ADMIN')");
    }
  }

  @Test
  @DisplayName(
      "the API lives under /api/v1/studios, the only prefix the edge sends to this service")
  void theApiIsReachableThroughTheEdge() {
    // orazaka-edge routes /api/v1/studios/** here and /api/v1/admin/** elsewhere: a path under
    // /api/v1/admin/studios answered 404 from the console. Same place as the operator audit.
    String path =
        StudioRuntimeConfigController.class.getAnnotation(RequestMapping.class).value()[0];
    assertThat(path).isEqualTo("/api/v1/studios/admin/runtime-config");
  }

  @Test
  @DisplayName("the list carries the typed value, the default and the bounds")
  void theListCarriesTheTypedContract() {
    when(runtimeConfig.all()).thenReturn(List.of(FAN_OUT));

    RuntimeConfigResponse row = controller.list().getFirst();

    assertThat(row.key()).isEqualTo("run.fan-out-max");
    assertThat(row.value()).isEqualTo(10);
    assertThat(row.type()).isEqualTo("int");
    assertThat(row.defaultValue()).isEqualTo(10);
    assertThat(row.bounds().minimum()).isEqualTo(1L);
    assertThat(row.bounds().maximum()).isEqualTo(1000L);
  }

  @Test
  @DisplayName("an update is made in the authenticated administrator's name")
  void anUpdateCarriesTheAuthenticatedActor() {
    Authentication operator = mock(Authentication.class);
    when(operator.getName()).thenReturn("operator-1");
    when(runtimeConfig.update(eq("run.fan-out-max"), any(), eq("operator-1"))).thenReturn(FAN_OUT);

    controller.update(
        "run.fan-out-max",
        new RuntimeConfigUpdateRequest(JsonNodeFactory.instance.numberNode(12)),
        operator);

    verify(runtimeConfig).update(eq("run.fan-out-max"), any(), eq("operator-1"));
  }
}
