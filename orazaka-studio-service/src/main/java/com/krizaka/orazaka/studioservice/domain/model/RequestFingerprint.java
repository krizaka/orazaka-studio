package com.krizaka.orazaka.studioservice.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * The SHA-256 of a request in canonical form: the order of a map's fields is not a difference, the
 * order of a list's items is. One definition for the two places that ask « is this the same
 * request? » — an {@code Idempotency-Key} replay (ADR-083) and the exact result cache
 * (orazaka-studio#61).
 */
public final class RequestFingerprint {

  private static final ObjectMapper JSON = new ObjectMapper();

  private RequestFingerprint() {}

  /**
   * @param scope what is asked, written first and quoted (a Studio, its version, an installation)
   * @param inputs the request's values, in any order
   * @return 64 lower-case hex characters
   */
  public static String of(String scope, Map<String, ?> inputs) {
    StringBuilder canonical = new StringBuilder();
    canonical.append(JSON.writeValueAsString(scope)).append('|');
    canonical(inputs == null ? Map.of() : inputs, canonical);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void canonical(Object value, StringBuilder out) {
    switch (value) {
      case Map<?, ?> map -> {
        List<String> keys = new ArrayList<>();
        map.keySet().forEach(k -> keys.add(String.valueOf(k)));
        keys.sort(null);
        out.append('{');
        for (int i = 0; i < keys.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          out.append(JSON.writeValueAsString(keys.get(i))).append(':');
          canonical(map.get(keys.get(i)), out);
        }
        out.append('}');
      }
      case Collection<?> items -> {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
          if (!first) {
            out.append(',');
          }
          first = false;
          canonical(item, out);
        }
        out.append(']');
      }
      case null, default -> out.append(JSON.writeValueAsString(value));
    }
  }
}
