package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studioservice.application.service.StudioRuntimeConfigService;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.RuntimeConfigResponse;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.RuntimeConfigUpdateRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator access to live Studio runtime settings. */
@RestController
@RequestMapping("/api/v1/admin/studios/runtime-config")
class StudioRuntimeConfigController {

  private final StudioRuntimeConfigService runtimeConfig;

  StudioRuntimeConfigController(StudioRuntimeConfigService runtimeConfig) {
    this.runtimeConfig =
        Objects.requireNonNull(runtimeConfig, "StudioRuntimeConfigService required");
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  List<RuntimeConfigResponse> list() {
    return runtimeConfig.all().stream().map(RuntimeConfigResponse::from).toList();
  }

  @PutMapping("/{key}")
  @PreAuthorize("hasRole('ADMIN')")
  RuntimeConfigResponse update(
      @PathVariable String key,
      @RequestBody RuntimeConfigUpdateRequest request,
      Authentication authentication) {
    return RuntimeConfigResponse.from(
        runtimeConfig.update(
            key, request == null ? null : request.value(), authentication.getName()));
  }
}
