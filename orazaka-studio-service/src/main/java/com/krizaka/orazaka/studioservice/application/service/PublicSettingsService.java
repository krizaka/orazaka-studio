package com.krizaka.orazaka.studioservice.application.service;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * The few runtime settings the public site may read without a token (orazaka-web-client#353).
 *
 * <p>An allow-list, not a filter: a key is public only because it is named in {@link #PUBLIC_KEYS},
 * the value is the row the admin edits in the console (audited there), and an absent or unreadable
 * row reads as the safe value. Nothing else of {@code studio_runtime_config} is reachable through
 * here.
 */
@Service
public class PublicSettingsService {

  /** The Mac build is handed out only while this is true. */
  static final String MAC_DOWNLOAD_KEY = "downloads.mac-enabled";

  /** The keys readable without authentication. */
  static final List<String> PUBLIC_KEYS = List.of(MAC_DOWNLOAD_KEY);

  /** What the download page needs: whether the Mac build is distributed. */
  public record Downloads(boolean macEnabled) {}

  private final StudioRuntimeConfigService runtimeConfig;

  public PublicSettingsService(StudioRuntimeConfigService runtimeConfig) {
    this.runtimeConfig =
        Objects.requireNonNull(runtimeConfig, "StudioRuntimeConfigService required");
  }

  /**
   * The download switches.
   *
   * @return the Mac switch; off when the row is absent or unreadable
   */
  public Downloads downloads() {
    return new Downloads(runtimeConfig.booleanValue(requirePublic(MAC_DOWNLOAD_KEY), false));
  }

  /**
   * Guards every read made for the public door.
   *
   * @param key the setting asked for
   * @return the same key
   * @throws IllegalArgumentException when the key is not on the allow-list
   */
  String requirePublic(String key) {
    if (key == null || !PUBLIC_KEYS.contains(key)) {
      throw new IllegalArgumentException("not a public setting: " + key);
    }
    return key;
  }
}
