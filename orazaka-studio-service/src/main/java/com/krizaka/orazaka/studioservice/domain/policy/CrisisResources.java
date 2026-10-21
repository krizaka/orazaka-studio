package com.krizaka.orazaka.studioservice.domain.policy;

import com.krizaka.orazaka.studio.domain.model.CrisisResource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a pack's verified crisis lines back out of its stored {@code safety} declaration.
 *
 * <p>One reader for the three places that need them - the consent notice a person reads before they
 * agree, the install check, the conversational turn - so none of them can disagree about what a
 * region's line is. Every resource is rebuilt through {@link CrisisResource}, which refuses one
 * without its source and the date it was verified.
 */
public final class CrisisResources {

  private CrisisResources() {}

  /**
   * Every resource the pack declared, in declaration order.
   *
   * @param safety the pack's stored safety declaration; may be empty
   * @return the verified lines; empty when the pack declared none
   */
  public static List<CrisisResource> of(Map<String, Object> safety) {
    if (safety == null || !(safety.get("resources") instanceof Map<?, ?> byRegion)) {
      return List.of();
    }
    List<CrisisResource> resources = new ArrayList<>();
    for (Object value : byRegion.values()) {
      if (value instanceof Map<?, ?> resource) {
        resources.add(read(resource));
      }
    }
    return List.copyOf(resources);
  }

  /**
   * The resource of one region.
   *
   * @param safety the pack's stored safety declaration
   * @param region the region code, {@code FR}, {@code CA-QC}...
   * @return the line, or empty when the pack never sourced that region
   */
  public static Optional<CrisisResource> forRegion(Map<String, Object> safety, String region) {
    if (region == null
        || safety == null
        || !(safety.get("resources") instanceof Map<?, ?> byRegion)
        || !(byRegion.get(region) instanceof Map<?, ?> resource)) {
      return Optional.empty();
    }
    return Optional.of(read(resource));
  }

  private static CrisisResource read(Map<?, ?> resource) {
    return new CrisisResource(
        String.valueOf(resource.get("region")),
        String.valueOf(resource.get("label")),
        String.valueOf(resource.get("contact")),
        String.valueOf(resource.get("availability")),
        String.valueOf(resource.get("sourceUrl")),
        LocalDate.parse(String.valueOf(resource.get("verifiedOn"))));
  }
}
