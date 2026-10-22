package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.jobs.domain.model.DataClass;
import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.CrisisResource;
import com.krizaka.orazaka.studio.domain.model.PackBlueprint;
import com.krizaka.orazaka.studio.domain.model.PackBundle;
import com.krizaka.orazaka.studio.domain.model.PackCatalogEntry;
import com.krizaka.orazaka.studio.domain.model.PackCategorySeed;
import com.krizaka.orazaka.studio.domain.model.PackConsent;
import com.krizaka.orazaka.studio.domain.model.PackDistribution;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.PackPricing;
import com.krizaka.orazaka.studio.domain.model.PackSafety;
import com.krizaka.orazaka.studio.domain.model.PackScopeGuard;
import com.krizaka.orazaka.studio.domain.model.PackStatus;
import com.krizaka.orazaka.studio.domain.model.PackStudio;
import com.krizaka.orazaka.studio.domain.model.PackTier;
import com.krizaka.orazaka.studio.domain.model.RegulatoryClass;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import com.krizaka.orazaka.studio.domain.model.StudioStatus;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * A changed consent statement reaches an environment that already carries the pack (orazaka#301).
 *
 * <p>"Applied" used to be judged by the Studios' blueprint versions alone, so consent 2.0 (new
 * words, an English translation) changed nothing on a database that had 1.0: the 451 kept serving
 * the old statement and nobody was asked again.
 */
class PackConsentAppliedIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";

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
  private static JdbcPackInstallRepositoryAdapter repository;

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
    repository =
        new JdbcPackInstallRepositoryAdapter(jdbc, new ColumnValueResolver(new ObjectMapper()));
  }

  @AfterAll
  static void stop() {
    dataSource.close();
    POSTGRES.stop();
  }

  private static PackBundle bundle(PackConsent consent) {
    return bundle(consent, null);
  }

  private static PackBundle bundle(PackConsent consent, String systemPrompt) {
    CrisisResource line =
        new CrisisResource(
            "FR", "3114", "3114", "24h/24", "https://3114.fr/", LocalDate.parse("2026-10-20"));
    return new PackBundle(
        PackBundle.API_VERSION,
        "bien-etre",
        "1.4.0",
        PackTier.DATA,
        PackDistribution.OSS,
        RegulatoryClass.REGULATED,
        PackKind.VERTICAL,
        List.of(),
        new PackCatalogEntry(
            "wellbeing", "sparkles", PackStatus.PUBLISHED, 0, new PackCategorySeed("heart", 60)),
        new PackPricing(0, 0, true),
        List.of(
            new PackStudio(
                "guided-journaling",
                "general",
                "notebook",
                StudioPricing.FREE,
                "studio.guided-journaling",
                StudioStatus.PUBLISHED,
                "orazaka",
                10,
                new PackBlueprint(
                    "1.2.0",
                    BlueprintStatus.PUBLISHED,
                    "{\"steps\":[]}",
                    "{}",
                    "{}",
                    100,
                    "initial",
                    "system"))),
        Map.of(),
        new PackScopeGuard(List.of("posologie"), "Je ne parle pas de posologie."),
        consent,
        new PackSafety(
            List.of("en finir"), "Je m'arrête là.", "review", Map.of("FR", line), systemPrompt));
  }

  @Test
  @DisplayName("a new consent version or a new translation makes the bundle un-applied again")
  void aChangedConsentIsNotApplied() {
    PackBundle v1 = bundle(new PackConsent("1.0", "Ce Studio enregistre."));
    PackBundle v2 =
        bundle(
            new PackConsent("2.0", "Ce Studio enregistre.", Map.of("en", "This Studio records.")));

    assertThat(repository.isApplied(v1)).as("nothing installed yet").isFalse();
    repository.apply(v1);
    assertThat(repository.isApplied(v1)).isTrue();

    assertThat(repository.isApplied(v2))
        .as("same Studios, a new statement: not applied until it is written")
        .isFalse();
    repository.apply(v2);

    assertThat(repository.isApplied(v2)).isTrue();
    assertThat(repository.isApplied(v1)).as("and the old one no longer is").isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT consent_statements->>'en' FROM pack WHERE pack_key = 'bien-etre'",
                String.class))
        .isEqualTo("This Studio records.");
    assertThat(DataClass.REGULATED.isProtected()).isTrue();
  }

  @Test
  @DisplayName(
      "a changed system prompt makes the bundle un-applied again, so it reaches the environment")
  void aChangedSystemPromptIsNotApplied() {
    PackConsent consent = new PackConsent("3.0", "Ce Studio enregistre.");
    PackBundle before = bundle(consent, "Tu es une présence IA.");
    PackBundle after = bundle(consent, "Tu es « le voisin ».");

    repository.apply(before);
    assertThat(repository.isApplied(before)).isTrue();

    assertThat(repository.isApplied(after))
        .as("same Studios, same consent, a new prompt: not applied until it is written")
        .isFalse();
    repository.apply(after);

    assertThat(repository.isApplied(after)).isTrue();
    assertThat(repository.isApplied(before)).as("and the old prompt no longer is").isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT safety->>'systemPrompt' FROM pack WHERE pack_key = 'bien-etre'",
                String.class))
        .isEqualTo("Tu es « le voisin ».");
  }
}
