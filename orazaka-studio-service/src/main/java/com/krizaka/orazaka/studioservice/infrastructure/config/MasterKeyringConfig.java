package com.krizaka.orazaka.studioservice.infrastructure.config;

import com.krizaka.orazaka.assets.domain.port.MasterKeyProvider;
import com.krizaka.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The platform's master keyring (ADR-054) that seals the webhook signing secrets: a file outside
 * the repository, mode 0600, written by the deployment before the JVMs start. A KMS-backed provider
 * would replace this bean without touching the sealer.
 */
@Configuration(proxyBeanMethods = false)
public class MasterKeyringConfig {

  @Bean
  MasterKeyProvider masterKeyProvider(
      @Value("${orazaka.assets.encryption.master-key-file}") String masterKeyFile) {
    return new FileMasterKeyProvider(Path.of(masterKeyFile));
  }
}
