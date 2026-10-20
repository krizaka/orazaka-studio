package com.krizaka.orazaka.studioservice.infrastructure.adapter.http;

import com.krizaka.orazaka.studioservice.domain.policy.WebhookTargetPolicy;
import com.krizaka.orazaka.studioservice.domain.port.WebhookSender;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Sends a webhook with the JDK client: never follows a redirect (a receiver could bounce the call
 * to an address the policy would have refused), checks the target with {@link WebhookTargetPolicy}
 * first, reads no body.
 */
@Component
class HttpWebhookSenderAdapter implements WebhookSender {

  private final HttpClient client =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  @Override
  public int post(String url, Map<String, String> headers, String body, Duration timeout)
      throws IOException {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException malformed) {
      throw new IOException("the webhook address is not a URL", malformed);
    }
    String host = uri.getHost();
    WebhookTargetPolicy.check(uri, host == null ? null : InetAddress.getAllByName(host));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(request::header);
    try {
      return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while sending the webhook", interrupted);
    }
  }
}
