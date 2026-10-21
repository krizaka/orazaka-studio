package com.krizaka.orazaka.studioservice.domain.port;

import java.util.Collection;

/**
 * Asks the job plane - which owns the jobs' rows and the files they wrote, on disk and in the
 * durable store - to erase the jobs of one person's runs (orazaka#301).
 *
 * <p>A port, because this service must not reach into another bounded context's tables or bucket
 * (SEAM-001): it knows <i>which</i> jobs belong to a person's runs, and the plane that holds the
 * material deletes it.
 */
public interface JobErasureClient {

  /**
   * Erases the jobs, idempotently: ids already gone count as nothing.
   *
   * @param actorId the person whose jobs these are; the plane deletes only rows that are theirs
   * @param jobIds the jobs of the runs being erased
   * @return how many jobs were erased
   * @throws com.krizaka.orazaka.studioservice.domain.exception.ErasureIncompleteException when the
   *     plane could not erase everything - the caller must not claim the erasure is done, and may
   *     ask again
   */
  int erase(String actorId, Collection<String> jobIds);
}
