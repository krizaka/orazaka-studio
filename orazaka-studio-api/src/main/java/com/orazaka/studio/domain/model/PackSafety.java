package com.orazaka.studio.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@code REGULATED} pack's crisis handling: what it watches for, what it answers, and where it
 * sends people.
 *
 * <p><b>The response is fixed and never generated.</b> A model asked to compose a crisis reply will
 * produce something that reads like one — including a phone number it invented. The pack author
 * writes this text, a human reviews it, and {@link #safetyReviewRef} records that review; the
 * engine renders it verbatim with the region's resource appended.
 *
 * @param crisisTerms what marks a turn as crisis content, declared by the pack in its own language
 * @param response the fixed reply, written by the pack author and reviewed by a person
 * @param safetyReviewRef what that review was — a ticket, a document, a name and a date. Recorded
 *     so "reviewed by a human" is a claim with something behind it
 * @param resources one verified crisis line per region the pack is available in; a region absent
 *     here is a region the pack does not install in
 */
public record PackSafety(
    List<String> crisisTerms,
    String response,
    String safetyReviewRef,
    Map<String, CrisisResource> resources) {

  /** Compact canonical constructor enforcing the declaration's invariants (ERR-106). */
  public PackSafety {
    if (crisisTerms == null || crisisTerms.isEmpty()) {
      throw new IllegalArgumentException(
          "a REGULATED pack must declare what it treats as crisis content");
    }
    crisisTerms = List.copyOf(crisisTerms);
    if (response == null || response.isBlank()) {
      throw new IllegalArgumentException(
          "a REGULATED pack must declare its fixed crisis response; a generated one hallucinates a"
              + " number");
    }
    if (safetyReviewRef == null || safetyReviewRef.isBlank()) {
      throw new IllegalArgumentException(
          "a REGULATED pack must record who reviewed its crisis response and when");
    }
    if (resources == null || resources.isEmpty()) {
      throw new IllegalArgumentException(
          "a REGULATED pack must ship at least one verified crisis resource; a pack available"
              + " everywhere and sourced nowhere is the failure this record exists to prevent");
    }
    resources = Map.copyOf(new LinkedHashMap<>(resources));
    for (Map.Entry<String, CrisisResource> entry : resources.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().region())) {
        throw new IllegalArgumentException(
            "crisis resource filed under '"
                + entry.getKey()
                + "' declares region '"
                + entry.getValue().region()
                + "'");
      }
    }
  }

  /** The regions this pack may be installed in, which is exactly the regions it has sourced. */
  public java.util.Set<String> availableRegions() {
    return resources.keySet();
  }

  /**
   * The full reply for a region: the reviewed text, then that region's verified line.
   *
   * @param region the installation's region
   * @return the fixed response, or {@code null} when the pack is not available there
   */
  public String responseFor(String region) {
    CrisisResource resource = resources.get(region);
    return resource == null ? null : response + "\n\n" + resource.render();
  }
}
