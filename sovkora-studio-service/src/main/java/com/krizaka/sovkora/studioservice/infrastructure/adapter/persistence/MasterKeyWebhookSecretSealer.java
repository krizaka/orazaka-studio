package com.krizaka.sovkora.studioservice.infrastructure.adapter.persistence;

import com.krizaka.sovkora.assets.domain.port.MasterKeyProvider;
import com.krizaka.sovkora.crypto.SecretSealer;
import com.krizaka.sovkora.studioservice.domain.port.WebhookSecretSealer;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Seals webhook secrets with the platform's sealer ({@link SecretSealer}, sovkora-crypto): the
 * mechanism of ADR-054 / ADR-084, bound to the endpoint's id.
 */
@Component
class MasterKeyWebhookSecretSealer implements WebhookSecretSealer {

  private final SecretSealer sealer;

  MasterKeyWebhookSecretSealer(MasterKeyProvider keys) {
    this.sealer = new SecretSealer(keys);
  }

  @Override
  public String seal(UUID endpointId, String plaintext) {
    return sealer.seal(endpointId, plaintext);
  }

  @Override
  public String open(UUID endpointId, String sealed) {
    return sealer.open(endpointId, sealed);
  }

  @Override
  public boolean isSealed(String stored) {
    return sealer.isSealed(stored);
  }
}
