package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studio.domain.model.StudioPricing;
import com.krizaka.orazaka.studio.domain.model.StudioStatus;
import com.krizaka.orazaka.studioservice.domain.exception.ConsentRequiredException;
import com.krizaka.orazaka.studioservice.domain.exception.ErasureIncompleteException;
import com.krizaka.orazaka.studioservice.domain.exception.PackNotFoundException;
import com.krizaka.orazaka.studioservice.domain.model.BlueprintVersion;
import com.krizaka.orazaka.studioservice.domain.model.ConsentNotice;
import com.krizaka.orazaka.studioservice.domain.model.ErasureReceipt;
import com.krizaka.orazaka.studioservice.domain.port.ConversationErasureClient;
import com.krizaka.orazaka.studioservice.domain.port.JobErasureClient;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import com.krizaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

/**
 * The right to erase (orazaka#301): against the <b>real</b> {@code 80-studio.sql}, a person asks
 * for everything a wellbeing pack holds of them to be erased, and the rows are gone - the runs with
 * their inputs and outputs, the steps and events, the messages and webhook deliveries built from
 * them, the installations with the consent they recorded - and the job plane was asked to erase the
 * jobs' rows and files. What is left is a row of the append-only trail with no content.
 *
 * <p>The proof is not a list of tables that happen to be checked: the person's words are searched
 * for in <b>every table of the schema</b>, so a copy in a table nobody thought of fails the test.
 */
class ProtectedDataServiceIT {

  private static final String STUDIO_DB = "orazaka_studio_db";
  private static final String STUDIO_ROLE = "orazaka_studio";
  private static final String STUDIO_PASSWORD = "orazaka_studio_pass";
  private static final String ERIC = "550e8400-e29b-41d4-a716-446655440099";
  private static final String OTHER = "550e8400-e29b-41d4-a716-446655440100";
  private static final String WORDS = "je n'arrive plus a dormir depuis la separation";
  private static final String OTHER_WORDS = "des mots qui ne sont pas a eric";

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

  private final FakeJobPlane jobPlane = new FakeJobPlane();
  private final FakeConversationPlane conversationPlane = new FakeConversationPlane();
  private final StudioRuntimeConfigService config = mock(StudioRuntimeConfigService.class);
  private final StudioRunService runService = mock(StudioRunService.class);
  private ProtectedDataService service;

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

    jdbc.update(
        "INSERT INTO pack_category (category_key, icon_key) VALUES ('wellbeing', 'heart')"
            + " ON CONFLICT DO NOTHING");
    jdbc.update(
        """
        INSERT INTO pack (pack_key, category_key, icon_key, regulatory_class, scope_guard,
                          consent_version, consent_statement, consent_statements, safety, status)
        VALUES ('bien-etre', 'wellbeing', 'sparkles', 'REGULATED',
                '{"refusedTerms":["posologie"],"refusal":"Je ne parle pas de posologie."}',
                '2.0', 'Ce Studio enregistre ce que vous écrivez.',
                '{"en":"This Studio records what you write."}',
                '{"crisisTerms":["en finir"],"response":"Je m''arrête là.","safetyReviewRef":"r",
                  "resources":{
                    "FR":{"region":"FR","label":"3114","contact":"3114",
                      "availability":"24h/24","sourceUrl":"https://3114.fr/",
                      "verifiedOn":"2026-10-20"},
                    "CA-QC":{"region":"CA-QC","label":"Prévention du suicide",
                      "contact":"1 866 APPELLE","availability":"En tout temps",
                      "sourceUrl":"https://www.quebec.ca/","verifiedOn":"2026-10-20"}}}',
                'PUBLISHED')
        """);
    jdbc.update(
        "INSERT INTO pack (pack_key, category_key, icon_key, regulatory_class, status)"
            + " VALUES ('trade', 'wellbeing', 'briefcase', 'STANDARD', 'PUBLISHED')");
    for (String studio : List.of("guided-journaling", "mood-tracking")) {
      jdbc.update(
          "INSERT INTO studio (studio_key, label, profession, icon_key, pricing, entitlement_key)"
              + " VALUES (?, ?, 'general', 'notebook', 'FREE', ?)",
          studio,
          studio,
          "studio." + studio);
      jdbc.update("INSERT INTO pack_studio (pack_key, studio_key) VALUES ('bien-etre', ?)", studio);
    }
    jdbc.update(
        "INSERT INTO studio (studio_key, label, profession, icon_key, pricing, entitlement_key)"
            + " VALUES ('trade-showcase', 'Vitrine', 'trades', 'studio', 'FREE',"
            + " 'studio.trade-showcase')");
    jdbc.update(
        "INSERT INTO pack_studio (pack_key, studio_key) VALUES ('trade', 'trade-showcase')");
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) {
      dataSource.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void freshData() {
    jdbc.update("DELETE FROM studio_webhook_delivery");
    jdbc.update("DELETE FROM studio_webhook_endpoint");
    jdbc.update("DELETE FROM studio_outbox");
    jdbc.update("DELETE FROM studio_run");
    jdbc.update("DELETE FROM studio_installation");
    // The trail is append-only on purpose: it is not reset, the tests count their own rows.
    when(config.intValue(eq("retention.sensitive-run-days"), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(30);
    service =
        new ProtectedDataService(
            jdbc,
            new ColumnValueResolver(new ObjectMapper()),
            config,
            runService,
            new RunAuditService(jdbc),
            jobPlane,
            conversationPlane);
  }

  private static UUID installation(String actor, String studio) {
    return jdbc.queryForObject(
        "INSERT INTO studio_installation (actor_id, studio_key, pinned_version, status,"
            + " consent_version, consent_recorded_at, age_attested_at, region)"
            + " VALUES (?, ?, '1.0.0', 'ACTIVE', '2.0', now(), now(), 'CA-QC') RETURNING id",
        UUID.class,
        actor,
        studio);
  }

  /** A run with its words in the inputs, the outputs, a step, a message and a webhook. */
  private static UUID run(
      String actor, String studio, UUID installation, String words, boolean done) {
    UUID run =
        jdbc.queryForObject(
            "INSERT INTO studio_run (installation_id, actor_id, studio_key, blueprint_version,"
                + " status, correlation_id, inputs, outputs, data_class, finished_at)"
                + " VALUES (?, ?, ?, '1.0.0', ?, ?, ?::jsonb, ?::jsonb, 'REGULATED', ?)"
                + " RETURNING id",
            UUID.class,
            installation,
            actor,
            studio,
            done ? "SUCCEEDED" : "RUNNING",
            UUID.randomUUID().toString(),
            "{\"entry\":\"" + words + "\"}",
            done ? "{\"journal\":\"" + words + " (mis au propre)\"}" : null,
            done ? java.sql.Timestamp.from(Instant.now()) : null);
    jdbc.update(
        "INSERT INTO studio_run_step (run_id, step_id, status, job_id, output)"
            + " VALUES (?, 'reflect', ?, ?, ?::jsonb)",
        run,
        done ? "SUCCEEDED" : "RUNNING",
        run.toString(),
        "{\"content\":\"" + words + "\"}");
    jdbc.update(
        "INSERT INTO studio_outbox (aggregate_id, event_type, payload) VALUES (?, 'job.text', ?::jsonb)",
        run.toString(),
        "{\"prompt\":\"" + words + "\"}");
    UUID endpoint =
        jdbc.queryForObject(
            "INSERT INTO studio_webhook_endpoint (actor_id, url, secret)"
                + " VALUES (?, 'https://example.org/hook', 'whsec_x') RETURNING id",
            UUID.class,
            actor);
    jdbc.update(
        "INSERT INTO studio_webhook_delivery (endpoint_id, run_id, event, payload)"
            + " VALUES (?, ?, 'run.completed', ?)",
        endpoint,
        run,
        "{\"outputs\":\"" + words + "\"}");
    return run;
  }

  private static int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  /** How many rows, in every table of the schema, mention these words. */
  private static int tablesMentioning(String words) {
    List<String> tables =
        jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
    int total = 0;
    for (String table : tables) {
      total +=
          count(
              "SELECT count(*) FROM \"" + table + "\" t WHERE t::text ILIKE ?", "%" + words + "%");
    }
    return total;
  }

  private static int auditRows(String actor, String event) {
    return count(
        "SELECT count(*) FROM studio_run_audit WHERE actor_id = ? AND event = ?", actor, event);
  }

  @Test
  @DisplayName("erase-all removes the person's wellbeing data everywhere, and only theirs")
  void eraseAllRemovesEverythingOfThePersonAndOnlyTheirs() {
    UUID journalInstall = installation(ERIC, "guided-journaling");
    UUID moodInstall = installation(ERIC, "mood-tracking");
    UUID first = run(ERIC, "guided-journaling", journalInstall, WORDS, true);
    UUID second = run(ERIC, "mood-tracking", moodInstall, WORDS, true);
    UUID active = run(ERIC, "guided-journaling", journalInstall, WORDS, false);
    UUID otherInstall = installation(OTHER, "guided-journaling");
    UUID othersRun = run(OTHER, "guided-journaling", otherInstall, OTHER_WORDS, true);
    assertThat(tablesMentioning(WORDS)).as("the words are stored before the erasure").isPositive();

    ErasureReceipt receipt = service.eraseAll(ERIC, "bien-etre");

    assertThat(receipt).isEqualTo(new ErasureReceipt("bien-etre", 3, 2, 3, 2));
    assertThat(tablesMentioning(WORDS)).as("the words, in any table of the schema").isZero();
    assertThat(count("SELECT count(*) FROM studio_run WHERE actor_id = ?", ERIC)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM studio_run_step WHERE run_id IN (?, ?, ?)",
                first,
                second,
                active))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM studio_run_event WHERE run_id IN (?, ?, ?)",
                first,
                second,
                active))
        .isZero();
    assertThat(count("SELECT count(*) FROM studio_installation WHERE actor_id = ?", ERIC))
        .as("the installations with the consent they recorded")
        .isZero();
    assertThat(jobPlane.erased)
        .as("the job plane was asked to erase exactly the jobs of Eric's runs")
        .containsExactlyInAnyOrder(first.toString(), second.toString(), active.toString());
    assertThat(jobPlane.actors).containsOnly(ERIC);
    assertThat(conversationPlane.asked)
        .as("the conversation service was asked to erase Eric's voice and listening memory")
        .containsExactly(ERIC + "/bien-etre");
    verify(runService).cancel(active, ERIC);
    verify(runService, never()).cancel(eq(first), anyString());

    assertThat(count("SELECT count(*) FROM studio_run WHERE id = ?", othersRun))
        .as("somebody else's entry is untouched")
        .isOne();
    assertThat(count("SELECT count(*) FROM studio_installation WHERE id = ?", otherInstall))
        .isOne();
    assertThat(tablesMentioning(OTHER_WORDS)).isPositive();
  }

  @Test
  @DisplayName("what remains is the proof of withdrawal: who, which Studio, which class, when")
  void theTrailKeepsTheProofAndNoContent() {
    UUID install = installation(ERIC, "guided-journaling");
    run(ERIC, "guided-journaling", install, WORDS, true);
    int before = auditRows(ERIC, "DATA_ERASED");

    service.eraseAll(ERIC, "bien-etre");

    assertThat(auditRows(ERIC, "DATA_ERASED")).isEqualTo(before + 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT row_to_json(a)::text FROM studio_run_audit a WHERE actor_id = ? AND event ="
                    + " 'DATA_ERASED' ORDER BY recorded_at DESC LIMIT 1",
                String.class,
                ERIC))
        .contains("guided-journaling", "bien-etre", "REGULATED", "recorded_at")
        .doesNotContain("dormir");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM studio_run_audit WHERE actor_id = ?", ERIC))
        .as("the trail itself is append-only")
        .hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("erase-all is idempotent: asking again erases nothing more and writes no new row")
  void eraseAllIsIdempotent() {
    UUID install = installation(ERIC, "mood-tracking");
    run(ERIC, "mood-tracking", install, WORDS, true);
    service.eraseAll(ERIC, "bien-etre");
    int trail = auditRows(ERIC, "DATA_ERASED");

    ErasureReceipt again = service.eraseAll(ERIC, "bien-etre");

    assertThat(again).isEqualTo(new ErasureReceipt("bien-etre", 0, 0, 0, 0));
    assertThat(auditRows(ERIC, "DATA_ERASED")).isEqualTo(trail);
  }

  @Test
  @DisplayName("if the job plane cannot erase its part, nothing is deleted and nothing is claimed")
  void aFailingJobPlaneDeletesNothing() {
    UUID install = installation(ERIC, "guided-journaling");
    run(ERIC, "guided-journaling", install, WORDS, true);
    int trail = auditRows(ERIC, "DATA_ERASED");
    jobPlane.failing = true;

    assertThatThrownBy(() -> service.eraseAll(ERIC, "bien-etre"))
        .isInstanceOf(ErasureIncompleteException.class);

    assertThat(count("SELECT count(*) FROM studio_run WHERE actor_id = ?", ERIC)).isOne();
    assertThat(count("SELECT count(*) FROM studio_installation WHERE actor_id = ?", ERIC)).isOne();
    assertThat(auditRows(ERIC, "DATA_ERASED")).isEqualTo(trail);

    jobPlane.failing = false;
    assertThat(service.eraseAll(ERIC, "bien-etre").entries()).isOne();
  }

  @Test
  @DisplayName("if the conversation service cannot erase its part, nothing is deleted either")
  void aFailingConversationPlaneDeletesNothing() {
    UUID install = installation(ERIC, "guided-journaling");
    run(ERIC, "guided-journaling", install, WORDS, true);
    int trail = auditRows(ERIC, "DATA_ERASED");
    conversationPlane.failing = true;

    assertThatThrownBy(() -> service.eraseAll(ERIC, "bien-etre"))
        .isInstanceOf(ErasureIncompleteException.class);

    assertThat(count("SELECT count(*) FROM studio_run WHERE actor_id = ?", ERIC)).isOne();
    assertThat(count("SELECT count(*) FROM studio_installation WHERE actor_id = ?", ERIC)).isOne();
    assertThat(auditRows(ERIC, "DATA_ERASED")).isEqualTo(trail);
    assertThat(jobPlane.erased).as("the job plane was not asked either").isEmpty();

    conversationPlane.failing = false;
    assertThat(service.eraseAll(ERIC, "bien-etre").entries()).isOne();
  }

  @Test
  @DisplayName("one entry can be erased on its own; the installation and its consent stay")
  void eraseOneEntry() {
    UUID install = installation(ERIC, "guided-journaling");
    UUID kept = run(ERIC, "guided-journaling", install, "mots a garder", true);
    UUID removed = run(ERIC, "guided-journaling", install, WORDS, true);
    UUID othersInstall = installation(OTHER, "guided-journaling");
    UUID othersRun = run(OTHER, "guided-journaling", othersInstall, OTHER_WORDS, true);
    int before = auditRows(ERIC, "RUN_ERASED");

    assertThat(service.eraseEntry(ERIC, removed)).isTrue();

    assertThat(tablesMentioning(WORDS)).isZero();
    assertThat(count("SELECT count(*) FROM studio_run WHERE id = ?", kept)).isOne();
    assertThat(count("SELECT count(*) FROM studio_installation WHERE id = ?", install)).isOne();
    assertThat(jobPlane.erased).containsExactly(removed.toString());
    assertThat(auditRows(ERIC, "RUN_ERASED")).isEqualTo(before + 1);

    assertThat(service.eraseEntry(ERIC, removed)).as("asking again").isFalse();
    assertThat(service.eraseEntry(ERIC, othersRun))
        .as("an entry that is somebody else's is not found, not erased")
        .isFalse();
    assertThat(count("SELECT count(*) FROM studio_run WHERE id = ?", othersRun)).isOne();
    assertThat(auditRows(ERIC, "RUN_ERASED")).isEqualTo(before + 1);
  }

  @Test
  @DisplayName("a pack that holds no protected data, or no pack, has nothing to erase")
  void unknownOrStandardPackIsNotFound() {
    assertThatThrownBy(() -> service.eraseAll(ERIC, "trade"))
        .isInstanceOf(PackNotFoundException.class);
    assertThatThrownBy(() -> service.eraseAll(ERIC, "nope"))
        .isInstanceOf(PackNotFoundException.class);
  }

  @Test
  @DisplayName("the consent notice is served in the caller's language with the verified lines")
  void theConsentNoticeIsServedInTheCallersLanguage() {
    ConsentNotice english = service.consentNotice("bien-etre", "en-CA");
    ConsentNotice french = service.consentNotice("bien-etre", "fr");
    ConsentNotice german = service.consentNotice("bien-etre", "de");

    assertThat(english.version()).isEqualTo("2.0");
    assertThat(english.statement()).isEqualTo("This Studio records what you write.");
    assertThat(french.statement()).isEqualTo("Ce Studio enregistre ce que vous écrivez.");
    assertThat(german.statement()).isEqualTo(french.statement());
    assertThat(english.statements()).containsOnlyKeys("fr", "en");
    assertThat(english.retentionDays()).isEqualTo(30);
    assertThat(english.regions())
        .extracting(region -> region.region() + " " + region.contact() + " " + region.sourceUrl())
        .containsExactlyInAnyOrder(
            "FR 3114 https://3114.fr/", "CA-QC 1 866 APPELLE https://www.quebec.ca/");
    assertThatThrownBy(() -> service.consentNotice("trade", "en"))
        .isInstanceOf(PackNotFoundException.class);
  }

  @Test
  @DisplayName("the 451 on install speaks the caller's language and carries the verified lines")
  void theInstallRefusalSpeaksTheCallersLanguage() {
    StudioCatalogService catalog = mock(StudioCatalogService.class);
    StudioAccessService access = mock(StudioAccessService.class);
    Studio studio =
        new Studio(
            "mood-tracking",
            "Mood",
            null,
            "general",
            "chart-line",
            null,
            StudioPricing.FREE,
            "bien-etre",
            PackKind.VERTICAL,
            "studio.mood-tracking",
            StudioStatus.PUBLISHED,
            "orazaka",
            "1.0.0",
            Set.of(),
            Instant.parse("2026-08-05T10:00:00Z"));
    when(catalog.find(eq("mood-tracking"), anyString())).thenReturn(Optional.of(studio));
    when(catalog.latestPublished("mood-tracking"))
        .thenReturn(
            Optional.of(
                new BlueprintVersion(
                    "mood-tracking", "1.0.0", BlueprintStatus.PUBLISHED, 10, null, null)));
    StudioInstallationService installs =
        new StudioInstallationService(
            jdbc, catalog, access, new ObjectMapper(), new ColumnValueResolver(new ObjectMapper()));

    assertThatThrownBy(() -> installs.install("mood-tracking", ERIC, null, "en", null))
        .isInstanceOfSatisfying(
            ConsentRequiredException.class,
            refused -> {
              assertThat(refused.requiredVersion()).isEqualTo("2.0");
              assertThat(refused.statement()).isEqualTo("This Studio records what you write.");
              assertThat(refused.statements()).containsOnlyKeys("fr", "en");
              assertThat(refused.regions())
                  .extracting("region")
                  .containsExactlyInAnyOrder("FR", "CA-QC");
            });
    assertThatThrownBy(() -> installs.install("mood-tracking", ERIC, null, "fr", null))
        .isInstanceOfSatisfying(
            ConsentRequiredException.class,
            refused ->
                assertThat(refused.statement())
                    .isEqualTo("Ce Studio enregistre ce que vous écrivez."));
  }

  /** The job plane, as the Studio service sees it: records what it was asked to erase. */
  private static final class FakeJobPlane implements JobErasureClient {
    final List<String> erased = new ArrayList<>();
    final List<String> actors = new ArrayList<>();
    boolean failing;

    @Override
    public int erase(String actorId, Collection<String> jobIds) {
      if (failing) {
        throw new ErasureIncompleteException("the job plane is down", null);
      }
      actors.add(actorId);
      erased.addAll(jobIds);
      return jobIds.size();
    }
  }

  /** The conversation service, as the Studio service sees it: records what it was asked. */
  private static final class FakeConversationPlane implements ConversationErasureClient {
    final List<String> asked = new ArrayList<>();
    boolean failing;
    private boolean erased;

    @Override
    public int erase(String actorId, String packKey) {
      if (failing) {
        throw new ErasureIncompleteException("the conversation service is down", null);
      }
      asked.add(actorId + "/" + packKey);
      // Idempotent like the real one: the second time there is nothing left.
      int conversations = erased ? 0 : 2;
      erased = true;
      return conversations;
    }
  }
}
