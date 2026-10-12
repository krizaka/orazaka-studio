package com.krizaka.orazaka.studio.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.krizaka.orazaka.studio.domain.exception.TurnConsentRequiredException;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpTurnDeclarationClientTest {

  private static final UUID ID = UUID.fromString("7a1c5d1e-0000-4000-8000-000000000001");
  private static final String URL =
      "http://studio/internal/v1/installations/" + ID + "/turn-declarations?actorId=actor-1";

  private MockRestServiceServer server;
  private HttpTurnDeclarationClient client;

  @BeforeEach
  void wire() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://studio");
    server = MockRestServiceServer.bindTo(builder).build();
    client = new HttpTurnDeclarationClient(builder.build());
  }

  @Test
  @DisplayName("Reads the pack's declarations, crisis line included")
  void readsTheDeclarations() {
    server
        .expect(requestTo(URL))
        .andRespond(
            withSuccess(
                """
                {"installationId":"%s","studioKey":"session-summary","packKey":"bien-etre",
                 "region":"CA-QC","scopeGuard":{"refusedTerms":["posologie"],"refusal":"Non."},
                 "crisisTerms":["en finir"],"crisisResponse":"Je m'arrête là.",
                 "crisisResource":{"region":"CA-QC","label":"L","contact":"1 866 APPELLE",
                   "availability":"24/7","sourceUrl":"https://www.quebec.ca/","verifiedOn":"2026-10-11"},
                 "systemPrompt":"Je suis une IA."}
                """
                    .formatted(ID),
                MediaType.APPLICATION_JSON));

    TurnDeclarations declared = client.forInstallation(ID, "actor-1").orElseThrow();

    assertThat(declared.guardsCrisis()).isTrue();
    assertThat(declared.crisisReply()).contains("1 866 APPELLE");
    assertThat(declared.scopeGuard().refusedTerms()).containsExactly("posologie");
    assertThat(declared.systemPrompt()).isEqualTo("Je suis une IA.");
  }

  @Test
  @DisplayName("404 is no installation of this actor — empty, never a guess")
  void notFoundIsEmpty() {
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(client.forInstallation(ID, "actor-1")).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("451 carries the statement through, verbatim")
  void consentIsRelayed() {
    server
        .expect(requestTo(URL))
        .andRespond(
            withStatus(HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"consentVersion\":\"1.1\",\"statement\":\"Ce Studio enregistre…\"}"));

    assertThatThrownBy(() -> client.forInstallation(ID, "actor-1"))
        .isInstanceOfSatisfying(
            TurnConsentRequiredException.class,
            refused -> {
              assertThat(refused.requiredVersion()).isEqualTo("1.1");
              assertThat(refused.statement()).isEqualTo("Ce Studio enregistre…");
            });
  }

  @Test
  @DisplayName("Anything else fails closed: a turn that cannot read its guards is not taken")
  void anOutageFailsClosed() {
    server.expect(requestTo(URL)).andRespond(withServerError());

    assertThatThrownBy(() -> client.forInstallation(ID, "actor-1"))
        .isInstanceOf(IllegalStateException.class);
  }
}
