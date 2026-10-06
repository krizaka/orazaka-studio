package com.orazaka.studioservice.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the service's {@link DataSource} explicitly from {@link StudioDataSourceProperties} so the
 * connection can only come from {@code STUDIO_DB_*} wiring — defining the bean makes Boot's {@code
 * spring.datasource} autoconfiguration (and any {@code SPRING_DATASOURCE_*} environment pollution)
 * back off entirely.
 */
@Configuration
@EnableConfigurationProperties(StudioDataSourceProperties.class)
class DataSourceConfig {

  @Bean
  DataSource studioDataSource(StudioDataSourceProperties properties) {
    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(properties.url());
    dataSource.setUsername(properties.username());
    dataSource.setPassword(properties.password());
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setMaximumPoolSize(5);
    dataSource.setMinimumIdle(1);
    return dataSource;
  }
}
