package com.orazaka.studioservice.infrastructure.adapter.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.orazaka.billing.domain.model.PackProvision;
import com.orazaka.billing.domain.port.PackProvisioningClient;
import com.orazaka.jobs.domain.model.CapabilityDeclaration;
import com.orazaka.jobs.domain.model.CapabilityRoute;
import com.orazaka.jobs.domain.port.CapabilityRegistrationClient;
import com.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.orazaka.studio.domain.model.PackBundle;
import com.orazaka.studioservice.application.service.PackInstallerService;
import com.orazaka.studioservice.domain.port.PackInstallRepository;
import com.orazaka.studioservice.infrastructure.adapter.persistence.PersistenceTestWiring;
import com.orazaka.studioservice.infrastructure.config.AssetStoreProperties;
import com.orazaka.studioservice.infrastructure.config.AssetStoreProperties.Deployment;
import com.orazaka.studioservice.infrastructure.config.PackSourceProperties;
import com.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import com.orazaka.studioservice.infrastructure.support.PackBundleResolver;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.orazaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * A fresh environment ends up with the packs this repository ships (ADR-068, {@code #45}).
 *
 * <p><b>From empty is the whole point.</b> The container runs {@code 80-studio.sql} and nothing
 * else — that file creates the schema and seeds runtime config, no pack and no Studio — so the
 * catalogue this suite starts from is exactly the catalogue a first {@code orazaka start} creates.
 * Before this run, that state was terminal: the bundle files sat in {@code orazaka-packs/} and the
 * only path into the database was an operator typing {@code orazaka pack install --all}. Closing
 * door 1 over that state would have deleted media generation rather than moved it.
 *
 * <p>The bundles are the real ones on disk, read by the real resolver through the real installer.
 * Only the two cross-context clients are stubs — capability registration belongs to the job service
 * and provisioning to billing, and a failure here must be unambiguously this service's.
 */
class PackBootstrapIT {

  /** These suites install the shipped bundles: they need orazaka-packs, i.e. the workspace. */
  @org.junit.jupiter.api.BeforeAll
  static void requireShippedPacks() {
    com.orazaka.test.architecture.Workspace.packs(
        java.nio.file.Path.of(System.getProperty("user.dir")));
  }

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";

  /** The pack door 1's closure depends on, and the six Studios it has to leave behind. */
  private static final String MEDIA_PACK = "orazaka-media";

  private static final List<String> MEDIA_STUDIOS =
      List.of(
          "image-generation",
          "video-generation",
          "speech-synthesis",
          "image-analysis",
          "audio-analysis",
          "video-analysis");

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

  private static AnnotationConfigApplicationContext context;
  private static JdbcTemplate jdbcTemplate;
  private static PackBundleResolver resolver;
  private static PackInstallRepository installRepository;
  private static RecordingProvisioningClient provisioning;

  /**
   * What the catalogue held before anything ran — the state a first {@code orazaka start} makes.
   */
  private static int studiosBeforeBootstrap;

  private static int packsBeforeBootstrap;

  /** The bundles the sources offered, read once so each test judges the same set. */
  private static List<Path> shipped;

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);
    context = new AnnotationConfigApplicationContext(BootstrapWiring.class);
    jdbcTemplate = context.getBean(JdbcTemplate.class);
    resolver = context.getBean(PackBundleResolver.class);
    installRepository = context.getBean(PackInstallRepository.class);
    provisioning = context.getBean(RecordingProvisioningClient.class);

    // Emptiness is measured before the first start, not restored between tests: a PUBLISHED
    // blueprint is append-only (trg_studio_blueprint_immutable), so there is no way back to an
    // empty catalogue and nothing in this suite may pretend otherwise.
    studiosBeforeBootstrap = count("studio");
    packsBeforeBootstrap = count("pack");
    shipped = resolver.discover();
    newBootstrap().install();
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @Test
  @DisplayName("a fresh start ends with every shipped bundle installed, from an empty catalogue")
  void aFreshStartInstallsEveryShippedBundle() {
    assertThat(shipped)
        .as("this repository ships bundles; a test that discovered none would assert nothing")
        .isNotEmpty();
    assertThat(studiosBeforeBootstrap)
        .as("the catalogue a first `orazaka start` creates: schema, runtime config, no Studio")
        .isZero();
    assertThat(packsBeforeBootstrap).as("and no pack").isZero();

    for (Path directory : shipped) {
      PackBundle bundle = resolver.read(directory);
      assertThat(installRepository.isApplied(bundle))
          .as("%s is shipped here and must be installed", bundle.key())
          .isTrue();
    }
    assertThat(count("studio"))
        .as("every Studio of every shipped bundle")
        .isEqualTo(shipped.stream().mapToInt(dir -> resolver.read(dir).studios().size()).sum());
  }

  @Test
  @DisplayName("the media pack's six Studios are published, with the blueprint each one runs")
  void theMediaToolkitIsWhatCloseDoorOneNeeds() {
    List<String> installed =
        jdbcTemplate.queryForList(
            "SELECT s.studio_key FROM studio s"
                + " JOIN studio_blueprint b ON b.studio_key = s.studio_key"
                + " AND b.version = s.latest_version"
                + " WHERE s.pack_key = ? AND s.status = 'PUBLISHED' AND b.status = 'PUBLISHED'"
                + " ORDER BY s.studio_key",
            String.class,
            MEDIA_PACK);

    assertThat(installed)
        .as("the six capabilities the composer is about to be served from")
        .containsExactlyInAnyOrderElementsOf(MEDIA_STUDIOS);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT kind FROM pack WHERE pack_key = ?", String.class, MEDIA_PACK))
        .as("a TOOLKIT is had by entitlement: no installation row is written for it")
        .isEqualTo("TOOLKIT");
    assertThat(provisioning.provisioned)
        .as("a catalogued pack's grants exist before the rows that promise them")
        .contains(MEDIA_PACK);
  }

  @Test
  @DisplayName("a second start installs nothing again, and leaves an admin's edit alone")
  void aSecondStartDoesNotOverwriteTheCatalogue() {
    jdbcTemplate.update(
        "UPDATE studio SET sort_weight = 4242 WHERE studio_key = ?", MEDIA_STUDIOS.get(0));
    provisioning.provisioned.clear();

    // A second PackBootstrap is a second start: the first instance settles and stops, which is
    // exactly what a restarted process does not remember.
    newBootstrap().install();

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT sort_weight FROM studio WHERE studio_key = ?",
                Integer.class,
                MEDIA_STUDIOS.get(0)))
        .as(
            "the catalogue is data an admin may change without a deploy; a bootstrap that"
                + " re-applied every shipped bundle at every start would undo that edit")
        .isEqualTo(4242);
    assertThat(provisioning.provisioned)
        .as("nothing was installed a second time, so nothing was provisioned a second time")
        .doesNotContain(MEDIA_PACK);
  }

  /** A bootstrap that has not run yet — what a restarted service has. */
  private static PackBootstrap newBootstrap() {
    return new PackBootstrap(
        resolver, context.getBean(PackInstallerService.class), installRepository);
  }

  /** How many rows one catalogue table holds. */
  private static int count(String table) {
    Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return rows == null ? 0 : rows;
  }

  /** Records what billing was asked to provision, so the order of the slices can be asserted. */
  static class RecordingProvisioningClient implements PackProvisioningClient {

    final List<String> provisioned = new CopyOnWriteArrayList<>();

    @Override
    public void provision(PackProvision provision) {
      provisioned.add(provision.packKey());
    }

    @Override
    public boolean withdraw(String packKey) {
      return provisioned.remove(packKey);
    }
  }

  /** Records the capabilities a Tier-C bundle contributes to the job plane. */
  static class RecordingRegistrationClient implements CapabilityRegistrationClient {

    final List<String> registered = new CopyOnWriteArrayList<>();

    @Override
    public void register(CapabilityDeclaration declaration) {
      registered.add(declaration.featureKey());
    }

    @Override
    public boolean unregister(String featureKey) {
      return registered.remove(featureKey);
    }
  }

  @Configuration
  @EnableTransactionManagement
  @Import(PersistenceTestWiring.class)
  static class BootstrapWiring {

    @Bean
    DataSource dataSource() {
      HikariDataSource pool = new HikariDataSource();
      pool.setJdbcUrl(
          "jdbc:postgresql://"
              + POSTGRES.getHost()
              + ":"
              + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
              + "/"
              + STUDIO_DB);
      pool.setUsername(STUDIO_ROLE);
      pool.setPassword(STUDIO_PASSWORD);
      pool.setDriverClassName("org.postgresql.Driver");
      return pool;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    ColumnValueResolver columnValueResolver(ObjectMapper objectMapper) {
      return new ColumnValueResolver(objectMapper);
    }

    /**
     * The shipped packs, resolved from the repository the way a local deployment resolves them: a
     * relative source, found by walking up from this module's directory.
     */
    @Bean
    PackSourceProperties packSourceProperties() {
      return new PackSourceProperties(List.of("orazaka-packs"));
    }

    @Bean
    PackBundleResolver packBundleResolver(
        ObjectMapper objectMapper, PackSourceProperties packSources) {
      return new PackBundleResolver(objectMapper, packSources);
    }

    /**
     * A developer's own machine, which is what a local phase is — {@code wellbeing} is REGULATED
     * and a deployment that stores documents in the clear may not hold it (ADR-051 §8).
     */
    @Bean
    AssetStoreProperties assetStoreProperties() {
      return new AssetStoreProperties(false, Deployment.LOCAL);
    }

    /** Every capability the shipped blueprints name resolves; the job plane owns the real table. */
    @Bean
    CapabilityRoutingClient capabilityRoutingClient() {
      return featureKey ->
          Optional.of(
              new CapabilityRoute(featureKey, "job.media.generate", null, "IMAGE", "BATCH", true));
    }

    @Bean
    RecordingRegistrationClient capabilityRegistrationClient() {
      return new RecordingRegistrationClient();
    }

    @Bean
    RecordingProvisioningClient packProvisioningClient() {
      return new RecordingProvisioningClient();
    }

    @Bean
    PackInstallerService packInstallerService(
        PackInstallRepository packInstallRepository,
        CapabilityRoutingClient capabilityRoutingClient,
        CapabilityRegistrationClient capabilityRegistrationClient,
        PackProvisioningClient packProvisioningClient,
        AssetStoreProperties assetStoreProperties) {
      return new PackInstallerService(
          packInstallRepository,
          capabilityRoutingClient,
          capabilityRegistrationClient,
          packProvisioningClient,
          assetStoreProperties);
    }
  }
}
