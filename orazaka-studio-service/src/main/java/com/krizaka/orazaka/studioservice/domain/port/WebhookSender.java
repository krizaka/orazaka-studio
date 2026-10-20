package com.krizaka.orazaka.studioservice.domain.port;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** Sends one webhook request; the adapter refuses addresses that are not the public internet. */
public interface WebhookSender {

  /**
   * POSTs {@code body} as JSON.
   *
   * @param url the receiver
   * @param headers the headers to add (the signature ones)
   * @param body the exact body
   * @param timeout how long the receiver has to answer
   * @return the HTTP status of the answer
   * @throws IOException when the receiver cannot be reached, answered too slowly, or its address is
   *     refused
   */
  int post(String url, Map<String, String> headers, String body, Duration timeout)
      throws IOException;
}
