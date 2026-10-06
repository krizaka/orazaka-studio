package com.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class StudioRuntimeConfigServiceTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final StudioRuntimeConfigService configService =
      new StudioRuntimeConfigService(jdbcTemplate);

  @Test
  @DisplayName("Reads a limit an admin set live")
  void readsConfiguredLimit() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
        .thenReturn(List.of("4"));

    assertThat(configService.intValue("run.fan-out-max", 10)).isEqualTo(4);
  }

  @Test
  @DisplayName("A deleted row reverts to the code default rather than breaking the run path")
  void missingRowFallsBack() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString())).thenReturn(List.of());

    assertThat(configService.intValue("run.fan-out-max", 10)).isEqualTo(10);
  }

  @Test
  @DisplayName("A malformed value falls back rather than throwing mid-run")
  void malformedValueFallsBack() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
        .thenReturn(List.of("many"));

    assertThat(configService.intValue("run.fan-out-max", 10)).isEqualTo(10);
  }
}
