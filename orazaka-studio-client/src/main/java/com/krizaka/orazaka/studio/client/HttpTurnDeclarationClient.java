package com.krizaka.orazaka.studio.client;

import com.krizaka.orazaka.studio.domain.exception.TurnConsentRequiredException;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import com.krizaka.orazaka.studio.domain.port.TurnDeclarationClient;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reads an installation's turn declarations over the studio service's internal surface (ADR-076).
 *
 * <p>Synchronous, because it is a precondition of an interactive turn (AGENTS.md §6). Fails closed:
 * a turn whose pack guards cannot be read is not taken, which is the posture of the guards
 * themselves (ADR-064) — a crisis guard that could not be loaded is a crisis guard that is absent.
 */
final class HttpTurnDeclarationClient implements TurnDeclarationClient {

  private final RestClient restClient;

  HttpTurnDeclarationClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "RestClient cannot be null");
  }

  @Override
  public Optional<TurnDeclarations> forInstallation(UUID installationId, String actorId) {
    try {
      return Optional.ofNullable(
          restClient
              .get()
              .uri(
                  "/internal/v1/installations/{id}/turn-declarations?actorId={actor}",
                  installationId,
                  actorId)
              .retrieve()
              .body(TurnDeclarations.class));
    } catch (RestClientResponseException refused) {
      if (refused.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
        return Optional.empty();
      }
      if (refused.getStatusCode().value() == HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS.value()) {
        Map<?, ?> body = refused.getResponseBodyAs(Map.class);
        throw new TurnConsentRequiredException(
            body == null ? null : String.valueOf(body.get("consentVersion")),
            body == null ? null : String.valueOf(body.get("statement")));
      }
      throw new IllegalStateException(
          "the studio service refused the turn declarations: " + refused.getStatusCode(), refused);
    } catch (RestClientException unreachable) {
      throw new IllegalStateException(
          "the studio service cannot be reached for the turn declarations", unreachable);
    }
  }
}
