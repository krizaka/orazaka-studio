package com.krizaka.orazaka.studioservice.domain.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** A webhook cannot be aimed at the service's own network (request forgery). */
class WebhookTargetPolicyTest {

  private static InetAddress[] ip(String literal) throws IOException {
    return new InetAddress[] {InetAddress.getByName(literal)};
  }

  private static void check(String url, String address) throws IOException {
    WebhookTargetPolicy.check(URI.create(url), ip(address));
  }

  @Test
  void acceptsAPublicHttpsAddress() {
    assertThatCode(() -> check("https://hooks.example.com/x", "93.184.216.34"))
        .doesNotThrowAnyException();
    assertThatCode(() -> check("https://hooks.example.com/x", "2606:2800:220:1:248:1893:25c8:1946"))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesAnythingButHttps() {
    assertThatThrownBy(() -> check("http://hooks.example.com/x", "93.184.216.34"))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> check("file:///etc/passwd", "93.184.216.34"))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> check("https://user:pw@hooks.example.com/x", "93.184.216.34"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void refusesEveryNonPublicAddress() {
    for (String address :
        new String[] {
          "127.0.0.1",
          "10.1.2.3",
          "172.16.0.9",
          "192.168.1.1",
          "169.254.169.254",
          "100.64.0.1",
          "0.0.0.0",
          "198.18.0.1",
          "224.0.0.1",
          "::1",
          "fe80::1",
          "fd00::1",
          "::ffff:10.0.0.1",
          "::ffff:127.0.0.1"
        }) {
      assertThatThrownBy(() -> check("https://hooks.example.com/x", address))
          .as(address)
          .isInstanceOf(IOException.class);
    }
  }

  @Test
  void aNameWithOnePrivateAnswerIsRefused() throws IOException {
    InetAddress[] mixed = {
      InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")
    };

    assertThatThrownBy(
            () -> WebhookTargetPolicy.check(URI.create("https://hooks.example.com/x"), mixed))
        .isInstanceOf(IOException.class);
  }

  @Test
  void refusesAHostThatDoesNotResolve() {
    assertThatThrownBy(
            () ->
                WebhookTargetPolicy.check(
                    URI.create("https://nowhere.example/x"), new InetAddress[0]))
        .isInstanceOf(IOException.class);
  }
}
