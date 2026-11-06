package com.krizaka.sovkora.studioservice.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.sovkora.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.AbstractContainerIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * The Studio schema is code (krizaka-internal#2255): {@code db/migration} applied by Flyway on an
 * empty database creates every table and the runtime-config defaults, as the database's owner role;
 * applied a second time it runs nothing and still validates.
 */
class StudioMigrationsIT extends AbstractContainerIntegrationTest {

  private static final String DATABASE = "studio_migrations_check";
  private static final long TABLES = 25;

  private static String url(String database) {
    String url = postgres().getJdbcUrl();
    return url.substring(0, url.lastIndexOf('/') + 1) + database;
  }

  private static Flyway flyway() {
    return Flyway.configure()
        .dataSource(url(DATABASE), postgres().getUsername(), postgres().getPassword())
        .locations("classpath:db/migration")
        .validateOnMigrate(true)
        .cleanDisabled(true)
        .load();
  }

  private static long count(Statement statement, String sql) throws Exception {
    try (ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  @Test
  void anEmptyDatabaseGetsTheWholeSchemaAndASecondRunAppliesNothing() throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                postgres().getJdbcUrl(), postgres().getUsername(), postgres().getPassword());
        Statement statement = admin.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
      statement.execute("CREATE DATABASE " + DATABASE);
    }

    assertThat(flyway().migrate().migrationsExecuted)
        .as("first run on an empty database")
        .isEqualTo(1);
    assertThat(flyway().migrate().migrationsExecuted).as("second run").isZero();
    flyway().validate();

    try (Connection connection =
            DriverManager.getConnection(
                url(DATABASE), postgres().getUsername(), postgres().getPassword());
        Statement statement = connection.createStatement()) {
      assertThat(
              count(
                  statement,
                  "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"
                      + " AND table_name <> 'flyway_schema_history'"))
          .as("the context's tables")
          .isEqualTo(TABLES);
      assertThat(
              count(
                  statement,
                  "SELECT count(*) FROM studio_runtime_config WHERE config_key ="
                      + " 'downloads.mac-enabled'"))
          .as("the reference data (runtime-config defaults)")
          .isEqualTo(1);
      assertThat(count(statement, "SELECT count(*) FROM operator_audit"))
          .as("the operator audit the old infra/migrations file added")
          .isZero();
    }
  }

  @Test
  void theLocalBootstrapCreatesOnlyTheRoleAndTheDatabase() throws Exception {
    // One source for the schema: a table in the initdb file would be a second copy, and the one a
    // deployed database never gets.
    String initdb =
        Files.readString(
            SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
                .resolve("80-studio.sql"));
    assertThat(initdb)
        .doesNotContainIgnoringCase("CREATE TABLE")
        .doesNotContainIgnoringCase("INSERT INTO");
  }
}
