package com.krizaka.sovkora.studioservice;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Builds the Studio schema in a test container the way the service does at startup: Flyway's {@code
 * db/migration} as the owner role (krizaka-internal#2255). The initdb file only creates the role
 * and the database.
 */
public final class StudioSchema {

  private static final String DATABASE = "orazaka_studio_db";

  private StudioSchema() {}

  /**
   * Migrates {@code orazaka_studio_db} of the container.
   *
   * @param postgres the started container, its initdb file run and the role's password set
   * @param role the owner role
   * @param password the role's password
   */
  public static void migrate(PostgreSQLContainer<?> postgres, String role, String password) {
    Flyway.configure()
        .dataSource(
            "jdbc:postgresql://%s:%d/%s"
                .formatted(postgres.getHost(), postgres.getMappedPort(5432), DATABASE),
            role,
            password)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }
}
