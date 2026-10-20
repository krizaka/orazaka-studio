package com.krizaka.orazaka.studioservice.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

  @Test
  void signsIdTimestampAndBodyWithHmacSha256() {
    // computed independently: base64(HMAC-SHA256("secret", "msg_1.1700000000.{\"a\":1}"))
    assertThat(WebhookSignature.sign("secret", "msg_1", 1_700_000_000L, "{\"a\":1}"))
        .isEqualTo("v1,Q5nZ7DiOGwijBBqvBVAK6c0zDr2JLkXI7TaV4f+Q2vY=");
  }

  @Test
  void anyChangeOfIdTimestampBodyOrSecretChangesTheSignature() {
    String base = WebhookSignature.sign("secret", "msg_1", 1_700_000_000L, "{}");

    assertThat(WebhookSignature.sign("secret", "msg_2", 1_700_000_000L, "{}")).isNotEqualTo(base);
    assertThat(WebhookSignature.sign("secret", "msg_1", 1_700_000_001L, "{}")).isNotEqualTo(base);
    assertThat(WebhookSignature.sign("secret", "msg_1", 1_700_000_000L, "{ }")).isNotEqualTo(base);
    assertThat(WebhookSignature.sign("other", "msg_1", 1_700_000_000L, "{}")).isNotEqualTo(base);
  }
}
