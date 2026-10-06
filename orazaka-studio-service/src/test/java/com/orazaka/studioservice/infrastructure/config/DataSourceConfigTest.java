package com.orazaka.studioservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataSourceConfigTest {

  @Test
  @DisplayName("The pool is built from the studio properties, never from spring.datasource")
  void poolBuiltFromStudioProperties() {
    var properties =
        new StudioDataSourceProperties(
            "jdbc:postgresql://localhost:5432/orazaka_studio_db", "orazaka_studio", "secret");

    try (HikariDataSource dataSource =
        (HikariDataSource) new DataSourceConfig().studioDataSource(properties)) {
      assertThat(dataSource.getJdbcUrl()).isEqualTo(properties.url());
      assertThat(dataSource.getUsername()).isEqualTo("orazaka_studio");
      assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
    }
  }
}
