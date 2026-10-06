package com.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class MessageDedupServiceTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final MessageDedupService dedupService = new MessageDedupService(jdbcTemplate);

  @Test
  @DisplayName("The first delivery wins the claim")
  void firstDeliveryWins() {
    when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(1);

    assertThat(dedupService.claim("studio-saga", "msg-1")).isTrue();
  }

  @Test
  @DisplayName("A redelivery loses on the unique key — no select-then-insert race")
  void redeliveryLoses() {
    when(jdbcTemplate.update(anyString(), anyString(), anyString()))
        .thenThrow(new DuplicateKeyException("already processed"));

    assertThat(dedupService.claim("studio-saga", "msg-1")).isFalse();
  }

  @Test
  @DisplayName("A message with no id is processed — there is nothing to deduplicate on")
  void messageWithoutIdIsProcessed() {
    assertThat(dedupService.claim("studio-saga", null)).isTrue();
    assertThat(dedupService.claim("studio-saga", " ")).isTrue();

    verify(jdbcTemplate, never()).update(anyString(), anyString(), anyString());
  }
}
