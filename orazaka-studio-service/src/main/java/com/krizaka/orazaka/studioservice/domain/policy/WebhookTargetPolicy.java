package com.krizaka.orazaka.studioservice.domain.policy;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

/**
 * Where a webhook may go: an {@code https} address that resolves to the public internet only.
 *
 * <p>The receiver's URL is typed by a client, so without this rule an API key could make the
 * service call its own database, the cloud metadata address or anything else on the private network
 * (request forgery). Loopback, private, link-local, multicast, unspecified and unique-local
 * addresses are refused, and so is a name with <em>any</em> such answer. The check is made on the
 * addresses the sender is about to use; the residual window between that lookup and the connection
 * (DNS rebinding) is closed by the network egress rules of the deployment, not here.
 */
public final class WebhookTargetPolicy {

  private WebhookTargetPolicy() {}

  /**
   * Refuses an address a webhook must not reach.
   *
   * @param uri the receiver
   * @param resolved every address its host resolves to
   * @throws IOException with the reason when the target is refused
   */
  public static void check(URI uri, InetAddress[] resolved) throws IOException {
    if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IOException("a webhook address must be https");
    }
    if (uri.getHost() == null || uri.getUserInfo() != null) {
      throw new IOException("a webhook address needs a host and no credentials");
    }
    if (resolved == null || resolved.length == 0) {
      throw new IOException("the webhook host does not resolve");
    }
    for (InetAddress address : resolved) {
      if (!isPublic(address)) {
        throw new IOException("the webhook host resolves to a non-public address");
      }
    }
  }

  static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] raw = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = raw[0] & 0xff;
      int second = raw[1] & 0xff;
      // 100.64.0.0/10 shared address space (carrier-grade NAT), 0.0.0.0/8, 192.0.0.0/24,
      // 198.18.0.0/15
      return !(first == 0
          || (first == 100 && second >= 64 && second <= 127)
          || (first == 192 && second == 0 && (raw[2] & 0xff) == 0)
          || (first == 198 && (second == 18 || second == 19)));
    }
    if (address instanceof Inet6Address) {
      // fc00::/7 unique-local; an IPv4-mapped address is judged by its IPv4 part
      if ((raw[0] & 0xfe) == 0xfc) {
        return false;
      }
      boolean mapped = true;
      for (int i = 0; i < 10; i++) {
        mapped &= raw[i] == 0;
      }
      if (mapped && (raw[10] & 0xff) == 0xff && (raw[11] & 0xff) == 0xff) {
        try {
          return isPublic(
              InetAddress.getByAddress(new byte[] {raw[12], raw[13], raw[14], raw[15]}));
        } catch (java.net.UnknownHostException impossible) {
          return false;
        }
      }
    }
    return true;
  }
}
