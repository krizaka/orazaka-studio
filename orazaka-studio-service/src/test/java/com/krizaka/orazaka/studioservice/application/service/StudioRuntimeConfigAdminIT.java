package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.studio.domain.model.StudioRuntimeConfig;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.orazaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/** Admin runtime changes against the real Studio schema and runtime-config table. */
class StudioRuntimeConfigAdminIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";
  private static final ObjectMapper JSON = new ObjectMapper();

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("bootstrap")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCopyFileToContainer(
              MountableFile.forHostPath(
                  SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
                      .resolve("80-studio.sql")),
              "/docker-entrypoint-initdb.d/80-studio.sql");

  private static HikariDataSource dataSource;
  private static JdbcTemplate jdbc;
  private StudioRuntimeConfigService service;

  @BeforeAll
  static void start() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + STUDIO_DB);
    dataSource.setUsername(STUDIO_ROLE);
    dataSource.setPassword(STUDIO_PASSWORD);
    jdbc = new JdbcTemplate(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) {
      dataSource.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void reset() {
    jdbc.update("TRUNCATE studio_runtime_config_audit RESTART IDENTITY");
    jdbc.update(
        "UPDATE studio_runtime_config SET config_value = default_value"
            + " WHERE config_key = 'run.fan-out-max'");
    service = new StudioRuntimeConfigService(jdbc);
  }

  @Test
  @DisplayName("Seeded config exposes defaults and persisted integer and list bounds")
  void listsSeededConfigMetadata() {
    List<StudioRuntimeConfig> all = service.all();

    assertThat(all)
        .filteredOn(config -> config.key().equals("run.fan-out-max"))
        .singleElement()
        .satisfies(
            config -> {
              assertThat(config.value()).isEqualTo(10);
              assertThat(config.defaultValue()).isEqualTo(10);
              assertThat(config.bounds().minimum()).isEqualTo(1);
              assertThat(config.bounds().maximum()).isEqualTo(1000);
            });
    assertThat(all)
        .filteredOn(config -> config.key().equals("run.result-cache.studios"))
        .singleElement()
        .satisfies(
            config -> {
              assertThat(config.value()).isEqualTo(List.of());
              assertThat(config.bounds().maxItems()).isEqualTo(100);
            });
  }

  @Test
  @DisplayName("A valid update is audited and the next runtime read sees it")
  void updateIsAuditedAndEffectiveImmediately() throws Exception {
    var updated = service.update("run.fan-out-max", JSON.readTree("12"), "admin-1");

    assertThat(updated.value()).isEqualTo(12);
    assertThat(service.intValue("run.fan-out-max", 10)).isEqualTo(12);
    assertThat(jdbc.queryForMap("SELECT * FROM studio_runtime_config_audit"))
        .containsEntry("actor_id", "admin-1")
        .containsEntry("config_key", "run.fan-out-max")
        .containsEntry("before_value", "10")
        .containsEntry("after_value", "12");
  }

  @Test
  @DisplayName("Wrong types and values outside the stored bounds are refused without an audit")
  void invalidValueDoesNotChangeOrAuditTheSetting() throws Exception {
    assertThatThrownBy(
            () -> service.update("run.fan-out-max", JSON.readTree("\"many\""), "admin-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> service.update("run.fan-out-max", JSON.readTree("1001"), "admin-1"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(service.intValue("run.fan-out-max", 10)).isEqualTo(10);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM studio_runtime_config_audit", Integer.class))
        .isZero();
  }

  @Test
  @DisplayName("Typed booleans and bounded string lists are stored in the runtime representation")
  void supportsBooleanAndListValues() throws Exception {
    service.update("marketplace.third-party-enabled", JSON.readTree("true"), "admin-1");
    service.update("run.result-cache.studios", JSON.readTree("[\"image-generation\"]"), "admin-1");

    assertThat(service.all())
        .filteredOn(config -> config.key().equals("marketplace.third-party-enabled"))
        .singleElement()
        .satisfies(config -> assertThat(config.value()).isEqualTo(true));
    assertThat(service.listValue("run.result-cache.studios"))
        .containsExactly("image-generation");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM studio_runtime_config_audit", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void listBoundIsEnforced() throws Exception {
    String tooManyItems =
        IntStream.range(0, 101)
            .mapToObj(index -> "\"studio-" + index + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    assertThatThrownBy(
            () ->
                service.update(
                    "run.result-cache.studios",
                    JSON.readTree(tooManyItems),
                    "admin-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
