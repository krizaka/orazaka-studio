package com.krizaka.orazaka.studioservice.domain.policy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs a webhook the way Standard Webhooks does, so a receiver verifies it with any off-the-shelf
 * library: {@code webhook-signature: v1,<base64 HMAC-SHA256>} over {@code id.timestamp.body}.
 * During a rotation both secrets sign, space separated; the receiver accepts either.
 */
public final class WebhookSignature {

  public static final String ID = "webhook-id";
  public static final String TIMESTAMP = "webhook-timestamp";
  public static final String SIGNATURE = "webhook-signature";

  private static final String ALGORITHM = "HmacSHA256";

  private WebhookSignature() {}

  /** {@code v1,<base64>} for one secret. */
  public static String sign(String secret, String id, long epochSeconds, String body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      byte[] digest =
          mac.doFinal((id + "." + epochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
      return "v1," + Base64.getEncoder().encodeToString(digest);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("HmacSHA256 is required by every JRE", impossible);
    }
  }

  /**
   * The three headers of a delivery.
   *
   * @param secret the current secret
   * @param previousSecret the secret still honoured after a rotation, or null
   * @param id the message id (stable across the retries of one delivery)
   * @param epochSeconds the time of this attempt
   * @param body the exact body
   * @return the headers
   */
  public static Map<String, String> headers(
      String secret, String previousSecret, String id, long epochSeconds, String body) {
    String signature = sign(secret, id, epochSeconds, body);
    if (previousSecret != null && !previousSecret.isBlank()) {
      signature += " " + sign(previousSecret, id, epochSeconds, body);
    }
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(ID, id);
    headers.put(TIMESTAMP, Long.toString(epochSeconds));
    headers.put(SIGNATURE, signature);
    return headers;
  }
}
