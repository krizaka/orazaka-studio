package com.krizaka.orazaka.studioservice.domain.port;

import com.krizaka.orazaka.studioservice.domain.model.ModelChoice;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port: the models the catalogue publishes for a category.
 *
 * <p>The catalogue belongs to the job plane (it owns {@code orazaka_models}); the Studio asks
 * rather than reading a table it does not own.
 */
public interface ModelOptionsClient {

  /**
   * @param category the model category ({@code image}, {@code video}, {@code speech}, …)
   * @return the published models, the ones that answer first; empty when the catalogue could not be
   *     reached — "unknown" is not "none", and a caller must not read it as a closed empty set
   */
  Optional<List<ModelChoice>> options(String category);

  /**
   * @param model the model a step names
   * @return the routing key the step must be published with instead of its capability's own, when the model is
   *     run by another queue (a model a provider hosts is routed by a cheap CPU worker, not the GPU worker); empty
   *     when the capability's key is right, or when the job plane could not be asked
   */
  default Optional<String> queueOf(String model) {
    return Optional.empty();
  }
}
