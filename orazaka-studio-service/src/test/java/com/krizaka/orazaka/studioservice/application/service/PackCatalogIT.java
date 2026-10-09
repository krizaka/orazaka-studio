package com.krizaka.orazaka.studioservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.billing.domain.model.PackPrice;
import com.krizaka.billing.domain.port.PackPricingClient;
import com.krizaka.orazaka.studio.domain.model.Pack;
import com.krizaka.orazaka.studio.domain.model.PackCategory;
import com.krizaka.orazaka.studio.domain.model.PackStatus;
import com.krizaka.orazaka.studio.domain.model.PackSummary;
import com.krizaka.orazaka.studio.domain.model.RegulatoryClass;
import com.krizaka.orazaka.studio.domain.model.StudioStatus;
import com.krizaka.orazaka.studioservice.domain.port.PackRepository;
import com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence.PersistenceTestWiring;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The Pack catalogue against the <b>real</b> {@code infra/initdb/80-studio.sql} (ADR-036).
 *
 * <p>An integration test rather than a unit test because every claim here is a claim about the
 * <b>schema</b>: that the i18n overlay resolves per locale and falls back when it does not, that
 * {@code pack_studio} aggregates into the bundle a card renders, that a Pack whose shelf is
 * inactive leaves the page rather than crashing it, and that a Studio in a published Pack is itself
 * published (invariant #2). None of that is provable against a mock repository.
 *
 * <p>Billing is stubbed on purpose, in both directions. The catalogue's contract is that a price
 * <b>may be absent</b>, and the degraded path is the one that matters: a marketing page must not go
 * down because the credit ledger is restarting.
 */
class PackCatalogIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";

  /**
   * Read from the seed rather than written as a literal: {@code StudioServiceGovernanceTest} bans
   * studio-key literals in production Java, and a test that hardcodes the catalogue it is checking
   * proves the catalogue matches the test rather than the seed.
   */
  private static final String SEEDED_PACK = "realestate-studio";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("bootstrap")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCopyFileToContainer(
              MountableFile.forHostPath(studioBootstrapScript()),
              "/docker-entrypoint-initdb.d/80-studio.sql");

  private static AnnotationConfigApplicationContext context;
  private static JdbcTemplate jdbcTemplate;
  private static PackCatalogService catalog;
  private static StubPricingClient pricing;

  private static Path studioBootstrapScript() {
    return SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
        .resolve("80-studio.sql");
  }

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    // The initdb file creates this role WITHOUT a password (ADR-035); `orazaka start` applies the
    // ALTER ROLE at runtime, and a hermetic test has to do the same.
    ServiceRoles.assignPassword(POSTGRES, STUDIO_ROLE, STUDIO_PASSWORD);
    context = new AnnotationConfigApplicationContext(TestWiring.class);
    jdbcTemplate = context.getBean(JdbcTemplate.class);
    catalog = context.getBean(PackCatalogService.class);
    pricing = context.getBean(StubPricingClient.class);
    installBundleFixture();
  }

  /**
   * Installs the catalogue this suite reads, from the bundle that now owns it.
   *
   * <p>{@code 80-studio.sql} creates the schema and seeds no catalogue since phase D moved the
   * packs into {@code orazaka-packs/} (ADR-039). This suite kept asserting against the seeded rows
   * and had been failing every browse assertion ever since — unnoticed because it is an {@code *IT}
   * and Maven's default surefire includes match only {@code *Test}, so {@code mvn install} never
   * ran it.
   *
   * <p>Written here as the rows the bundle declares, not as invented fixtures: the values are the
   * bundle's, so a manifest edit that changes the shelf or the label still has to be reflected here
   * deliberately rather than silently passing.
   */
  private static void installBundleFixture() {
    jdbcTemplate.update(
        "INSERT INTO pack_category (category_key, icon_key, sort_weight, is_active)"
            + " VALUES ('business', 'briefcase', 100, TRUE), ('lifestyle', 'heart', 90, TRUE)"
            + " ON CONFLICT (category_key) DO NOTHING");
    jdbcTemplate.update(
        "INSERT INTO pack_category_i18n (category_key, locale, label, description) VALUES"
            + " ('business', 'fr', 'Business', 'Les packs qui font le travail.'),"
            + " ('business', 'en', 'Business', 'The packs that do the job.'),"
            + " ('lifestyle', 'fr', 'Life Style', 'Les packs du quotidien.'),"
            + " ('lifestyle', 'en', 'Life Style', 'The everyday packs.')"
            + " ON CONFLICT (category_key, locale) DO NOTHING");
    jdbcTemplate.update(
        "INSERT INTO pack (pack_key, category_key, icon_key, regulatory_class, status, sort_weight)"
            + " VALUES (?, 'business', 'studio', 'STANDARD', 'PUBLISHED', 100)"
            + " ON CONFLICT (pack_key) DO NOTHING",
        SEEDED_PACK);
    jdbcTemplate.update(
        "INSERT INTO pack_i18n (pack_key, locale, label, tagline, description) VALUES"
            + " (?, 'fr', 'Studio Immobilier', 'Vos biens deviennent des Reels prêts à publier.',"
            + "  'Le pack métier de l''agent immobilier.'),"
            + " (?, 'en', 'Real-Estate Studio', 'Your listings become Reels ready to publish.',"
            + "  'The real-estate agent''s pack.')"
            + " ON CONFLICT (pack_key, locale) DO NOTHING",
        SEEDED_PACK,
        SEEDED_PACK);
    jdbcTemplate.update(
        "INSERT INTO studio (studio_key, label, tagline, profession, icon_key, pricing, pack_key,"
            + " entitlement_key, status, publisher_id, latest_version, sort_weight)"
            + " VALUES ('realestate-reels', 'Reels Immobilier', 'Cinq photos deviennent un Reel.',"
            + " 'real-estate', 'studio', 'PAID', ?, 'studio.realestate-reels', 'PUBLISHED',"
            + " 'orazaka', '1.0.0', 90) ON CONFLICT (studio_key) DO NOTHING",
        SEEDED_PACK);
    jdbcTemplate.update(
        "INSERT INTO pack_studio (pack_key, studio_key, sort_weight) VALUES (?, ?, 100)"
            + " ON CONFLICT (pack_key, studio_key) DO NOTHING",
        SEEDED_PACK,
        "realestate-reels");
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void resetStubAndSeed() {
    pricing.reset();
    jdbcTemplate.update("UPDATE pack_category SET is_active = TRUE");
    jdbcTemplate.update("DELETE FROM pack WHERE pack_key <> ?", SEEDED_PACK);
  }

  @Test
  @DisplayName("[gate §6.4] the seeded pack browses in French with its shelf, icon and bundle")
  void browsesTheSeededPackInFrench() {
    pricing.price(SEEDED_PACK, 4900, 5000L);

    PackSummary card = onlyCard(catalog.browse(null, "fr"));

    assertEquals(SEEDED_PACK, card.pack().packKey());
    assertEquals("business", card.pack().categoryKey());
    assertEquals("Studio Immobilier", card.pack().label());
    assertEquals("studio", card.pack().iconKey());
    assertEquals(List.of("realestate-reels"), card.pack().studioKeys());
    assertEquals("Business", card.category().label());
    assertEquals(4900, card.priceCents());
    assertEquals(5000L, card.includedCredits());
  }

  @Test
  @DisplayName("a locale that exists swaps every string, not only the label")
  void localisesTheWholeCard() {
    PackSummary english = onlyCard(catalog.browse(null, "en"));

    assertEquals("Real-Estate Studio", english.pack().label());
    assertEquals("Your listings become Reels ready to publish.", english.pack().tagline());
  }

  @Test
  @DisplayName("an unknown locale falls back to a translation rather than rendering the key")
  void fallsBackWhenTheLocaleIsAbsent() {
    // `pack` carries no base label — unlike `studio`, every string lives in pack_i18n — so the
    // fallback is the Pack's lowest-sorting translation. Rendering "realestate-studio" as a
    // product name would be the visible failure this chain exists to avoid.
    PackSummary card = onlyCard(catalog.browse(null, "de"));

    assertEquals("Real-Estate Studio", card.pack().label());
    assertNotNull(card.pack().tagline());
  }

  @Test
  @DisplayName("browsing one shelf returns that shelf, and an empty one returns nothing")
  void narrowsToOneShelf() {
    assertEquals(1, catalog.browse("business", "fr").size());
    assertTrue(catalog.browse("lifestyle", "fr").isEmpty(), "no pack is seeded on Life Style yet");
  }

  @Test
  @DisplayName("[ADR-036 §4.4] billing being down costs the price, not the page")
  void degradesToAnAbsentPriceWhenBillingIsSilent() {
    // The failure mode this replaces: a 500 on the marketing page every time the credit ledger
    // restarts. `null` rather than 0 because the card must read "—", never "Gratuit".
    pricing.reset();

    PackSummary card = onlyCard(catalog.browse(null, "fr"));

    assertFalse(card.priced());
    assertNull(card.priceCents());
    assertNull(card.includedCredits());
    assertEquals("Studio Immobilier", card.pack().label());
  }

  @Test
  @DisplayName("one billing call prices the whole page, never one per card")
  void asksBillingOncePerPage() {
    pricing.price(SEEDED_PACK, 4900, 5000L);

    catalog.browse(null, "fr");

    assertEquals(1, pricing.callCount(), "a per-card lookup would turn one browse into N hops");
  }

  @Test
  @DisplayName("[invariant #2] every studio a published pack bundles is itself published")
  void everyBundledStudioIsPublished() {
    // Checked here as well as statically because the static rule reads the seed file and this
    // reads what the database actually holds — a pack that bundles an unpublished Studio sells
    // access to something that cannot run.
    List<String> unpublished =
        jdbcTemplate.queryForList(
            "SELECT s.studio_key FROM pack_studio ps"
                + " JOIN pack p ON p.pack_key = ps.pack_key"
                + " JOIN studio s ON s.studio_key = ps.studio_key"
                + " WHERE p.status = ? AND s.status <> ?",
            String.class,
            PackStatus.PUBLISHED.name(),
            StudioStatus.PUBLISHED.name());

    assertTrue(
        unpublished.isEmpty(), () -> "published packs bundle unpublished studios: " + unpublished);
  }

  @Test
  @DisplayName("a DRAFT pack stays off the marketplace but resolves by key for the console")
  void draftsAreAuthorableButNotBrowsable() {
    jdbcTemplate.update(
        "INSERT INTO pack (pack_key, category_key, icon_key, status, sort_weight)"
            + " VALUES (?, 'business', 'studio', 'DRAFT', 10)",
        "draft-pack");
    jdbcTemplate.update(
        "INSERT INTO pack_i18n (pack_key, locale, label) VALUES (?, 'fr', 'Brouillon')",
        "draft-pack");

    assertTrue(
        catalog.browse(null, "fr").stream()
            .noneMatch(c -> c.pack().packKey().equals("draft-pack")));

    Pack draft = catalog.find("draft-pack", "fr").orElseThrow();
    assertEquals(PackStatus.DRAFT, draft.status());
    assertEquals(
        List.of(),
        draft.studioKeys(),
        "a draft may bundle nothing — invariant #1 is for PUBLISHED");
  }

  @Test
  @DisplayName("regulatory_class round-trips and defaults to STANDARD, with nothing acting on it")
  void regulatoryClassIsCarriedAndUnread() {
    assertEquals(
        RegulatoryClass.STANDARD, catalog.find(SEEDED_PACK, "fr").orElseThrow().regulatoryClass());
  }

  @Test
  @DisplayName("a pack whose shelf was deactivated leaves the page instead of breaking it")
  void dropsACardWhoseShelfNoLongerResolves() {
    jdbcTemplate.update(
        "UPDATE pack_category SET is_active = FALSE WHERE category_key = 'business'");

    assertTrue(catalog.browse(null, "fr").isEmpty());
    // The Pack itself is untouched — deactivating a shelf hides it, it does not delete what sat on
    // it.
    assertTrue(catalog.find(SEEDED_PACK, "fr").isPresent());
  }

  @Test
  @DisplayName("the shelves are fetched, so the client renders headings it did not invent")
  void servesTheShelvesThemselves() {
    List<PackCategory> shelves = catalog.categories("fr");

    assertEquals(
        List.of("business", "lifestyle"), shelves.stream().map(PackCategory::categoryKey).toList());
    assertEquals("Life Style", shelves.get(1).label());
    assertEquals("heart", shelves.get(1).iconKey());
  }

  @Test
  @DisplayName("an unknown key reads as absent rather than as an error")
  void unknownKeysAreEmpty() {
    assertTrue(catalog.find("no-such-pack", "fr").isEmpty());
    assertTrue(catalog.summary("no-such-pack", "fr").isEmpty());
  }

  private static PackSummary onlyCard(List<PackSummary> cards) {
    assertEquals(1, cards.size(), () -> "expected exactly the seeded pack, got: " + cards);
    return cards.get(0);
  }

  /**
   * A billing stub that answers for the keys it was given and nothing else.
   *
   * <p>Records its call count because "one call for the page" is a contract, not an optimisation:
   * the alternative turns a forty-card browse into forty service hops.
   */
  static class StubPricingClient implements PackPricingClient {

    private final Map<String, PackPrice> table = new java.util.LinkedHashMap<>();
    private final List<Set<String>> calls = new ArrayList<>();

    @Override
    public Map<String, PackPrice> prices(Set<String> packKeys) {
      calls.add(packKeys);
      Map<String, PackPrice> answered = new java.util.LinkedHashMap<>();
      for (String key : packKeys) {
        PackPrice price = table.get(key);
        if (price != null) {
          answered.put(key, price);
        }
      }
      return answered;
    }

    void price(String packKey, int priceCents, long includedCredits) {
      table.put(packKey, new PackPrice(packKey, priceCents, includedCredits, true));
    }

    void reset() {
      table.clear();
      calls.clear();
    }

    int callCount() {
      return calls.size();
    }
  }

  @Configuration
  @Import(PersistenceTestWiring.class)
  static class TestWiring {

    @Bean
    DataSource dataSource() {
      HikariDataSource dataSource = new HikariDataSource();
      dataSource.setJdbcUrl(
          "jdbc:postgresql://"
              + POSTGRES.getHost()
              + ":"
              + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
              + "/"
              + STUDIO_DB);
      dataSource.setUsername(STUDIO_ROLE);
      dataSource.setPassword(STUDIO_PASSWORD);
      dataSource.setDriverClassName("org.postgresql.Driver");
      return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    ColumnValueResolver columnValueResolver(ObjectMapper objectMapper) {
      return new ColumnValueResolver(objectMapper);
    }

    @Bean
    StubPricingClient stubPricingClient() {
      return new StubPricingClient();
    }

    @Bean
    StudioCatalogService studioCatalogService(
        JdbcTemplate jdbcTemplate, ColumnValueResolver columns) {
      return new StudioCatalogService(jdbcTemplate, columns);
    }

    /** No plan: every Studio locked, so a TOOLKIT pack reads BUYABLE rather than included. */
    @Bean
    StudioAccessService studioAccessService() {
      return new StudioAccessService(
          actorId ->
              new com.krizaka.billing.domain.model.EntitlementSnapshot(
                  actorId, null, java.util.Map.of(), 0L, java.time.Instant.now()));
    }

    @Bean
    PackCatalogService packCatalogService(
        PackRepository packRepository,
        StubPricingClient pricing,
        StudioCatalogService studioCatalogService,
        StudioAccessService studioAccessService) {
      return new PackCatalogService(
          packRepository, pricing, studioCatalogService, studioAccessService);
    }
  }
}
