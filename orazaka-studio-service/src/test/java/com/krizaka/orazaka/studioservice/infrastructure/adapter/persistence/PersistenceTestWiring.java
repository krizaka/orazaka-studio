package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository;
import com.krizaka.orazaka.studioservice.domain.port.PackInstallRepository;
import com.krizaka.orazaka.studioservice.domain.port.PackRepository;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Exposes the pack-private persistence adapters to integration tests.
 *
 * <p>Lives in the adapter's own package because the adapter is package-private, which is the point:
 * production code depends on the {@link BlueprintRepository} port and cannot reach the class. A
 * test that needs the real thing declares that need here rather than widening the adapter's
 * visibility for everyone (ERR-110).
 */
@Configuration
public class PersistenceTestWiring {

  /**
   * The real JDBC adapter, so an integration test parses the real seeded blueprint.
   *
   * @param jdbcTemplate the studio datasource
   * @param objectMapper the JSON reader
   * @return the adapter behind its port
   */
  @Bean
  public BlueprintRepository blueprintRepository(
      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    return new JdbcBlueprintRepositoryAdapter(jdbcTemplate, objectMapper);
  }

  /**
   * The real Pack catalogue adapter, so an integration test reads the real seeded catalogue.
   *
   * @param jdbcTemplate the studio datasource
   * @param columns the timestamp resolver shared by the read adapters
   * @return the adapter behind its port
   */
  @Bean
  public PackRepository packRepository(JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
    return new JdbcPackRepositoryAdapter(jdbcTemplate, columns);
  }

  /**
   * The real install adapter, so an integration test writes the catalogue the way an install does.
   *
   * @param jdbcTemplate the studio datasource
   * @param columns the JSON writer shared by the adapters
   * @return the adapter behind its port
   */
  @Bean
  public PackInstallRepository packInstallRepository(
      JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
    return new JdbcPackInstallRepositoryAdapter(jdbcTemplate, columns);
  }
}
