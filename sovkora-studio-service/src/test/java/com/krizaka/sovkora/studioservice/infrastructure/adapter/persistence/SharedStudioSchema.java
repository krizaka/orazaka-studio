package com.krizaka.sovkora.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.sovkora.studioservice.StudioSchema;
import com.krizaka.sovkora.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import java.nio.file.Path;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Builds the Studio schema in the shared container of {@code AbstractContainerIntegrationTest}
 * once: {@code 80-studio.sql} creates the role and {@code orazaka_studio_db}, then Flyway applies
 * {@code db/migration}; a second class asking for it must find it, not build it again.
 */
final class SharedStudioSchema {

  static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";

  private SharedStudioSchema() {}

  /**
   * @param postgres the shared container
   * @throws Exception when the container cannot be driven
   */
  static synchronized void ensureLoaded(PostgreSQLContainer<?> postgres) throws Exception {
    var exists =
        postgres.execInContainer(
            "psql",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "-tAc",
            "SELECT 1 FROM pg_database WHERE datname = '" + STUDIO_DB + "'");
    if ("1".equals(exists.getStdout().trim())) {
      return;
    }
    postgres.copyFileToContainer(
        MountableFile.forHostPath(
            SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
                .resolve("80-studio.sql")),
        "/tmp/80-studio.sql");
    var created =
        postgres.execInContainer(
            "psql",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "-v",
            "ON_ERROR_STOP=1",
            "-f",
            "/tmp/80-studio.sql");
    assertThat(created.getExitCode()).as(created.getStderr()).isZero();
    ServiceRoles.assignPassword(postgres, STUDIO_ROLE, STUDIO_PASSWORD);
    StudioSchema.migrate(postgres, STUDIO_ROLE, STUDIO_PASSWORD);
  }
}
