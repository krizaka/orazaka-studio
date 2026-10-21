package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studioservice.application.service.ProtectedDataService;
import com.krizaka.orazaka.studioservice.domain.model.ErasureReceipt;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto.ErasureResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

class ProtectedDataControllerTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final Jwt ACTOR_JWT =
      Jwt.withTokenValue("t").header("alg", "HS256").subject(ACTOR).build();

  private final ProtectedDataService service = mock(ProtectedDataService.class);
  private final ProtectedDataController controller = new ProtectedDataController(service);

  @Test
  @DisplayName("erase-all acts for the token's subject, never for an id in the request")
  void eraseAllActsForTheTokenSubject() {
    when(service.eraseAll(ACTOR, "bien-etre"))
        .thenReturn(new ErasureReceipt("bien-etre", 3, 2, 3, 2));

    ErasureResponse response = controller.eraseAll("bien-etre", ACTOR_JWT);

    assertThat(response).isEqualTo(new ErasureResponse("bien-etre", 3, 2, 3, 2));
  }

  @Test
  @DisplayName("erasing an entry answers 204 whether or not it existed")
  void eraseEntryAnswers204() {
    UUID run = UUID.randomUUID();

    var response = controller.eraseEntry(run, ACTOR_JWT);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(service).eraseEntry(ACTOR, run);
  }
}
