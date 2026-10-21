package com.krizaka.orazaka.studioservice.domain.port;

/**
 * Asks the conversation service - which keeps the memory of a person's voice and listening turns -
 * to erase it (orazaka#301). A port, because this service must not reach into another bounded
 * context's tables (SEAM-001): it knows <i>whose</i> data and under <i>which pack</i>, and the
 * service that holds the messages deletes them.
 */
public interface ConversationErasureClient {

  /**
   * Erases every conversation of one person under one pack, idempotently.
   *
   * @param actorId the person
   * @param packKey the regulated pack
   * @return how many conversations were erased
   * @throws com.krizaka.orazaka.studioservice.domain.exception.ErasureIncompleteException when the
   *     service could not erase - the caller must not claim the erasure is done, and may ask again
   */
  int erase(String actorId, String packKey);
}
