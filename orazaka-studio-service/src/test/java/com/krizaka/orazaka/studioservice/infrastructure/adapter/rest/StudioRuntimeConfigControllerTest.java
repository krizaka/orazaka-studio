package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.krizaka.orazaka.studio.domain.model.StudioRuntimeConfig;
import com.krizaka.orazaka.studioservice.application.service.StudioRuntimeConfigService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

@WebMvcTest(StudioRuntimeConfigController.class)
@Import({RestErrorResolver.class, StudioRuntimeConfigControllerTest.MethodSecurity.class})
class StudioRuntimeConfigControllerTest {

  private static final String ENDPOINT = "/api/v1/admin/studios/runtime-config";
  private static final StudioRuntimeConfig FAN_OUT =
      new StudioRuntimeConfig(
          "run.fan-out-max",
          10,
          "int",
          "Hard cap on forEach expansion.",
          10,
          new StudioRuntimeConfig.Bounds(1L, 1000L, null));

  @Autowired private MockMvc mvc;

  @MockitoBean private StudioRuntimeConfigService runtimeConfig;

  @Test
  @WithMockUser(roles = "USER")
  void nonAdminsCannotReadRuntimeSettings() throws Exception {
    mvc.perform(get(ENDPOINT)).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(username = "operator-1", roles = "ADMIN")
  void adminCanReadTheTypedContractIncludingDefaultAndBounds() throws Exception {
    when(runtimeConfig.all()).thenReturn(List.of(FAN_OUT));

    mvc.perform(get(ENDPOINT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value("run.fan-out-max"))
        .andExpect(jsonPath("$[0].value").value(10))
        .andExpect(jsonPath("$[0].type").value("int"))
        .andExpect(jsonPath("$[0].default").value(10))
        .andExpect(jsonPath("$[0].bounds.minimum").value(1))
        .andExpect(jsonPath("$[0].bounds.maximum").value(1000));
  }

  @Test
  @WithMockUser(username = "operator-1", roles = "ADMIN")
  void adminUpdatesArePassedWithTheAuthenticatedActor() throws Exception {
    when(runtimeConfig.update(eq("run.fan-out-max"), any(), eq("operator-1")))
        .thenReturn(FAN_OUT);

    mvc.perform(
            put(ENDPOINT + "/run.fan-out-max")
                .contentType("application/json")
                .content("{\"value\":12}"))
        .andExpect(status().isOk());

    verify(runtimeConfig).update(eq("run.fan-out-max"), any(), eq("operator-1"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void badRuntimeValuesAreReturnedAsBadRequests() throws Exception {
    when(runtimeConfig.update(eq("run.fan-out-max"), any(), any()))
        .thenThrow(new IllegalArgumentException("value must be an integer"));

    mvc.perform(
            put(ENDPOINT + "/run.fan-out-max")
                .contentType("application/json")
                .content("{\"value\":\"many\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("invalid_request"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void nonAdminsCannotUpdateRuntimeSettings() throws Exception {
    mvc.perform(
            put(ENDPOINT + "/run.fan-out-max")
                .contentType("application/json")
                .content("{\"value\":12}"))
        .andExpect(status().isForbidden());
  }

  @Configuration
  @EnableMethodSecurity
  static class MethodSecurity {}
}
