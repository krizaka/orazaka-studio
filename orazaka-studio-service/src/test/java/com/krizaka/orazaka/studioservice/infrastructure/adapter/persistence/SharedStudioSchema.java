package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import java.nio.file.Path;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Loads {@code 80-studio.sql} into the shared container of {@code AbstractContainerIntegrationTest}
 * once: the file creates {@code orazaka_studio_db}, so a second class asking for it must find it,
 * not load it again.
 */
final class SharedStudioSchema {

  static final String STUDIO_DB = "orazaka_studio_db";

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
    if (exists.getStdout().trim().equals("1")) {
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
  }
}
