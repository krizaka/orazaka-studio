package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.studioservice.application.service.PublicSettingsService;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.PublicDownloadsResponse;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settings the public site reads without a token. Anonymous on purpose (the chain opens only
 * {@code GET /api/v1/studios/public/**}); what it may say is the allow-list of {@link
 * PublicSettingsService}, and every answer is cacheable for a minute.
 */
@RestController
@RequestMapping("/api/v1/studios/public")
class PublicSettingsController {

  /** How long a browser, the edge or the site may reuse an answer. @fixed: a cache lifetime. */
  private static final Duration CACHE = Duration.ofMinutes(1);

  private final PublicSettingsService publicSettings;

  PublicSettingsController(PublicSettingsService publicSettings) {
    this.publicSettings = Objects.requireNonNull(publicSettings, "PublicSettingsService required");
  }

  @GetMapping("/downloads")
  ResponseEntity<PublicDownloadsResponse> downloads() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(CACHE).cachePublic())
        .body(new PublicDownloadsResponse(publicSettings.downloads().macEnabled()));
  }
}
