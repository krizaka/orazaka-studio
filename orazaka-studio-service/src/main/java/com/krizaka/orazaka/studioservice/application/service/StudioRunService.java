package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.jobs.domain.model.DataClass;
import com.krizaka.orazaka.studio.domain.exception.StudioNotEntitledException;
import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintOutput;
import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import com.krizaka.orazaka.studio.domain.model.PackKind;
import com.krizaka.orazaka.studio.domain.model.RegulatoryClass;
import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studio.domain.model.RunStepStatus;
import com.krizaka.orazaka.studio.domain.model.Studio;
import com.krizaka.orazaka.studioservice.domain.exception.InstallationNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.RunNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioNotFoundException;
import com.krizaka.orazaka.studioservice.domain.exception.StudioNotInstalledException;
import com.krizaka.orazaka.studioservice.domain.model.InstalledStudio;
import com.krizaka.orazaka.studioservice.domain.model.RunArtefact;
import com.krizaka.orazaka.studioservice.domain.model.RunDetail;
import com.krizaka.orazaka.studioservice.domain.model.RunStepView;
import com.krizaka.orazaka.studioservice.domain.port.BlueprintRepository;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The run lifecycle: start, read, approve, cancel.
 *
 * <p>The DAG itself is {@link RunSagaService}'s job. This service owns what the API surface needs —
 * authorisation, the credit hold, and the scoping that keeps one actor's runs invisible to another.
 *
 * <p>Order matters in {@link #start}: validate, then hold, then persist, then dispatch. Holding
 * before persisting means a crash leaves a reservation the billing sweeper expires; persisting
 * before holding would mean submitting work nobody authorised, which is the failure ADR-033 exists
 * to prevent.
 */
@Service
public class StudioRunService {

  private static final String SELECT_RUN =
      "SELECT id, installation_id, actor_id, studio_key, blueprint_version, status, hold_id,"
          + " correlation_id, inputs, outputs, error_message, started_at, finished_at"
          + " FROM studio_run";

  private final JdbcTemplate jdbcTemplate;
  private final StudioInstallationService installationService;
  private final BlueprintRepository blueprintRepository;
  private final CreditReservationService creditReservationService;
  private final RunSettlementService settlementService;
  private final RunSagaService runSagaService;
  private final StudioRuntimeConfigService runtimeConfigService;
  private final OutboxService outboxService;
  private final ObjectMapper objectMapper;
  private final ColumnValueResolver columns;
  private final RunAuditService runAuditService;
  private final StudioCatalogService catalogService;
  private final StudioAccessService accessService;

  public StudioRunService(
      JdbcTemplate jdbcTemplate,
      StudioInstallationService installationService,
      BlueprintRepository blueprintRepository,
      CreditReservationService creditReservationService,
      RunSettlementService settlementService,
      RunSagaService runSagaService,
      StudioRuntimeConfigService runtimeConfigService,
      OutboxService outboxService,
      ObjectMapper objectMapper,
      ColumnValueResolver columns,
      RunAuditService runAuditService,
      StudioCatalogService catalogService,
      StudioAccessService accessService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.installationService = Objects.requireNonNull(installationService, "installations");
    this.blueprintRepository = Objects.requireNonNull(blueprintRepository, "blueprints");
    this.creditReservationService = Objects.requireNonNull(creditReservationService, "credits");
    this.settlementService =
        Objects.requireNonNull(settlementService, "RunSettlementService required");
    this.runSagaService = Objects.requireNonNull(runSagaService, "saga required");
    this.runtimeConfigService = Objects.requireNonNull(runtimeConfigService, "config required");
    this.outboxService = Objects.requireNonNull(outboxService, "OutboxService required");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
    this.columns = Objects.requireNonNull(columns, "ColumnValueResolver required");
    this.runAuditService = Objects.requireNonNull(runAuditService, "RunAuditService required");
    this.catalogService = Objects.requireNonNull(catalogService, "StudioCatalogService required");
    this.accessService = Objects.requireNonNull(accessService, "StudioAccessService required");
  }

  /**
   * Starts a run of an installation.
   *
   * @param installationId the installation to execute
   * @param actorId the opaque billable subject
   * @param inputs the actor's answers, validated server-side against the blueprint's schema
   * @return the accepted run
   * @throws InstallationNotFoundException when the id names nothing this actor owns
   */
  @Transactional
  public RunDetail start(UUID installationId, String actorId, Map<String, Object> inputs) {
    InstalledStudio installation =
        installationService
            .find(installationId, actorId)
            .orElseThrow(() -> new InstallationNotFoundException(installationId));

    if (installation.status() == InstallationStatus.PAUSED
        || installation.status() == InstallationStatus.REVOKED) {
      throw new IllegalStateException(
          "installation is " + installation.status() + " and cannot run");
    }
    return launch(
        installationId, installation.studioKey(), installation.pinnedVersion(), actorId, inputs);
  }

  /**
   * Starts a run of a Studio, addressed by the Studio rather than by an installation (ADR-061).
   *
   * <p>The one entry a TOOLKIT can be run through: its installation is derived from entitlement and
   * has no id to put in a URL. A VERTICAL is reachable here too, through the actor's installation
   * and exactly the checks the installation entry applies — which is what lets the two refusals
   * meet in one place and take one shape: an unentitled TOOLKIT and an uninstalled VERTICAL both
   * answer {@code 409} carrying the pack to open.
   *
   * <p><b>A TOOLKIT floats; a VERTICAL pins.</b> With no installation there is no pin, so the
   * version is resolved here, once — the same {@code latestPublished} a fresh install would pin —
   * and written to {@code studio_run.blueprint_version}. Nothing resolves it again: every advance
   * of the DAG reads that column. A version published while the run is in flight is therefore not a
   * version this run can execute, and a run can never have executed two versions of its blueprint.
   *
   * @param studioKey the Studio to run
   * @param actorId the opaque billable subject
   * @param inputs the actor's answers, validated server-side against the blueprint's schema
   * @return the accepted run
   * @throws StudioNotFoundException when the key names no Studio
   * @throws StudioNotEntitledException when the actor is not entitled — for either kind
   * @throws StudioNotInstalledException when the actor is entitled to a VERTICAL but has not
   *     installed it
   */
  @Transactional
  public RunDetail start(String studioKey, String actorId, Map<String, Object> inputs) {
    Studio studio =
        catalogService
            .find(studioKey, null)
            .orElseThrow(() -> new StudioNotFoundException(studioKey));

    if (studio.kind() == PackKind.TOOLKIT) {
      if (!accessService.isIncluded(studio, actorId)) {
        // Not included means locked: this throws the entitlement refusal, carrying the pack.
        accessService.requireEntitled(studio, actorId);
      }
      String resolved =
          catalogService
              .latestPublished(studioKey)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "studio has no published version to run: " + studioKey))
              .version();
      return launch(null, studioKey, resolved, actorId, inputs);
    }

    Optional<InstalledStudio> installation = installationService.findForStudio(studioKey, actorId);
    if (installation.isPresent()) {
      return start(installation.get().id(), actorId, inputs);
    }
    // An actor who could not install it hears that first, exactly as the install path says it.
    accessService.requireEntitled(studio, actorId);
    throw new StudioNotInstalledException(
        studio.studioKey(), studio.entitlementKey(), studio.packKey());
  }

  /**
   * The run itself, once the entry has decided which version runs and for which installation.
   *
   * <p>One body for both kinds, so a TOOLKIT run is not a second implementation of a run that could
   * drift from the first. Order matters, as the class comment says: validate, hold, persist,
   * dispatch.
   *
   * @param installationId the installation, or {@code null} for a TOOLKIT's derived one
   * @param studioKey the Studio
   * @param version the blueprint version this run executes, decided before this call and never
   *     after
   * @param actorId the opaque billable subject
   * @param inputs the actor's answers
   * @return the accepted run
   */
  private RunDetail launch(
      UUID installationId,
      String studioKey,
      String version,
      String actorId,
      Map<String, Object> inputs) {
    enforceConcurrencyCap(actorId);

    Blueprint blueprint =
        blueprintRepository
            .find(studioKey, version)
            .orElseThrow(
                () -> new IllegalStateException("pinned version no longer exists: " + version));

    Map<String, Object> validated = validateInputs(blueprint, inputs);
    String correlationId = UUID.randomUUID().toString();
    String holdId =
        creditReservationService.hold(actorId, correlationId, blueprint.estimatedCredits());

    UUID runId = UUID.randomUUID();
    // Stamped once, from the pack this Studio belongs to (ADR-051). Read at run time and stored on
    // the run rather than resolved on demand: the obligation attaches to the data, and a pack that
    // is later withdrawn, re-classified or uninstalled must not be able to declassify runs that
    // already happened.
    DataClass dataClass = dataClassOf(studioKey);
    jdbcTemplate.update(
        "INSERT INTO studio_run (id, installation_id, actor_id, studio_key, blueprint_version,"
            + " status, hold_id, correlation_id, inputs, data_class)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
        runId,
        installationId,
        actorId,
        studioKey,
        version,
        RunStatus.RUNNING.name(),
        holdId,
        correlationId,
        columns.json(validated),
        dataClass.name());
    runAuditService.recordStarted(runId, actorId, studioKey, dataClass);

    if (installationId != null) {
      jdbcTemplate.update(
          "UPDATE studio_installation SET last_run_at = now() WHERE id = ?", installationId);
    }
    outboxService.append(
        runId.toString(),
        "evt.studio.run.started",
        Map.of(
            "runId",
            runId.toString(),
            "actorId",
            actorId,
            "studioKey",
            studioKey,
            "correlationId",
            correlationId));

    runSagaService.advance(runId);
    return find(runId, actorId).orElseThrow(() -> new RunNotFoundException(runId));
  }

  /**
   * One run with its steps and outputs, scoped to its owner.
   *
   * @param runId the run
   * @param actorId the opaque billable subject
   * @return the run, or empty when the id names nothing this actor owns
   */
  public Optional<RunDetail> find(UUID runId, String actorId) {
    return resolveArtefacts(
            jdbcTemplate.query(
                SELECT_RUN + " WHERE id = ? AND actor_id = ?",
                (rs, rowNum) -> readRun(rs),
                runId,
                actorId))
        .stream()
        .findFirst()
        .map(run -> run.withSteps(stepsOf(run.id())));
  }

  /**
   * This actor's run history.
   *
   * @param actorId the opaque billable subject
   * @param limit how many to return
   * @return their runs, most recent first
   */
  public List<RunDetail> history(String actorId, int limit) {
    return resolveArtefacts(
        jdbcTemplate.query(
            SELECT_RUN + " WHERE actor_id = ? ORDER BY started_at DESC LIMIT ?",
            (rs, rowNum) -> readRun(rs),
            actorId,
            limit));
  }

  /**
   * Resolves an approval step, resuming the run.
   *
   * @param runId the parked run
   * @param actorId the opaque billable subject
   * @throws RunNotFoundException when the id names nothing this actor owns
   */
  @Transactional
  public void approve(UUID runId, String actorId) {
    RunDetail run = find(runId, actorId).orElseThrow(() -> new RunNotFoundException(runId));
    if (run.status() != RunStatus.AWAITING_INPUT) {
      throw new IllegalStateException("run is not awaiting input: " + run.status());
    }
    jdbcTemplate.update(
        "UPDATE studio_run_step SET status = ?, finished_at = now()"
            + " WHERE run_id = ? AND status = ? AND job_id IS NULL",
        RunStepStatus.SUCCEEDED.name(),
        runId,
        RunStepStatus.PENDING.name());
    jdbcTemplate.update(
        "UPDATE studio_run SET status = ? WHERE id = ?", RunStatus.RUNNING.name(), runId);
    runSagaService.advance(runId);
  }

  /**
   * Cancels a run and releases its hold.
   *
   * <p>In-flight jobs are allowed to finish and their results discarded: killing a running MLX job
   * is not free either, and a half-killed accelerator job is worse than a wasted one (ADR-034
   * §7.1).
   *
   * @param runId the run
   * @param actorId the opaque billable subject
   * @throws RunNotFoundException when the id names nothing this actor owns
   */
  @Transactional
  public void cancel(UUID runId, String actorId) {
    RunDetail run = find(runId, actorId).orElseThrow(() -> new RunNotFoundException(runId));
    if (run.finishedAt() != null) {
      return;
    }
    jdbcTemplate.update(
        "UPDATE studio_run SET status = ?, finished_at = now() WHERE id = ? AND finished_at IS NULL",
        RunStatus.CANCELLED.name(),
        runId);
    jdbcTemplate.update(
        "UPDATE studio_run_step SET status = ? WHERE run_id = ? AND status IN (?, ?)",
        RunStepStatus.CANCELLED.name(),
        runId,
        RunStepStatus.PENDING.name(),
        RunStepStatus.RUNNING.name());
    // One author closes a hold (ADR-059). This method decides the run is cancelled.
    settlementService.releaseAbandoned(run.holdId(), "cancelled by actor");
  }

  private void enforceConcurrencyCap(String actorId) {
    int cap = runtimeConfigService.intValue("run.max-concurrent-per-actor", 2);
    Integer active =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM studio_run WHERE actor_id = ? AND finished_at IS NULL",
            Integer.class,
            actorId);
    if (active != null && active >= cap) {
      throw new IllegalStateException(
          "too many concurrent runs: " + active + " of " + cap + " already running");
    }
  }

  /**
   * Checks the run inputs against the blueprint's JSON Schema, server-side.
   *
   * <p>Required properties only. The client-side form is convenience, not enforcement (ADR-034
   * §18), and this is the last gate before a hold is taken — but full type checking would need a
   * JSON Schema validator dependency, so what is enforced here is exactly what is claimed:
   * presence.
   */
  /**
   * The data class a run of this Studio carries, from the pack that ships it.
   *
   * <p>A Studio with no pack — the trade-showcase shape, installed before packs were bundles — is
   * STANDARD, which is what it has always been. Defaulting the other way would reclassify every
   * existing run the first time somebody forgot a pack row.
   */
  private DataClass dataClassOf(String studioKey) {
    return jdbcTemplate
        .query(
            "SELECT p.regulatory_class FROM pack_studio ps"
                + " JOIN pack p ON p.pack_key = ps.pack_key WHERE ps.studio_key = ?",
            (rs, rowNum) -> RegulatoryClass.valueOf(rs.getString(1)).dataClass(),
            studioKey)
        .stream()
        .findFirst()
        .orElse(DataClass.STANDARD);
  }

  private Map<String, Object> validateInputs(Blueprint blueprint, Map<String, Object> inputs) {
    Map<String, Object> supplied = inputs == null ? Map.of() : inputs;
    var schema = objectMapper.readTree(blueprint.inputSchema());
    var required = schema.path("required");
    if (required.isArray()) {
      List<String> missing =
          java.util.stream.StreamSupport.stream(required.spliterator(), false)
              .map(node -> node.asString())
              .filter(key -> !supplied.containsKey(key))
              .toList();
      if (!missing.isEmpty()) {
        throw new IllegalArgumentException("missing required inputs: " + missing);
      }
    }
    return Map.copyOf(supplied);
  }

  private List<RunStepView> stepsOf(UUID runId) {
    return jdbcTemplate.query(
        "SELECT step_id, ordinal, job_id, status, attempts, error_message FROM studio_run_step"
            + " WHERE run_id = ? ORDER BY started_at NULLS FIRST, step_id, ordinal",
        (rs, rowNum) ->
            new RunStepView(
                rs.getString("step_id"),
                rs.getInt("ordinal"),
                rs.getString("job_id"),
                RunStepStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("error_message")),
        runId);
  }

  /**
   * Attaches each run's declared label and rendering type to its outputs.
   *
   * <p>Memoised per blueprint version rather than looked up per run: a history page holds many runs
   * of few Studios, and a lookup per row would be the N+1 [ERR-109] bans. The page is
   * limit-bounded, so the memo costs at most one read per distinct version on the page.
   *
   * @param runs runs carrying raw outputs
   * @return the same runs, their outputs resolved
   */
  private List<RunDetail> resolveArtefacts(List<RawRun> runs) {
    Map<String, List<BlueprintOutput>> declared = new HashMap<>();
    return runs.stream()
        .map(
            raw -> {
              List<BlueprintOutput> outputs =
                  declared.computeIfAbsent(
                      raw.run().studioKey() + "@" + raw.run().blueprintVersion(),
                      ignored ->
                          blueprintRepository
                              .find(raw.run().studioKey(), raw.run().blueprintVersion())
                              .map(Blueprint::outputs)
                              .orElseGet(List::of));
              return raw.run().withOutputs(artefacts(raw.values(), outputs));
            })
        .toList();
  }

  /**
   * Joins produced values onto their declarations, in the order the blueprint declares them.
   *
   * <p>Declaration order, not map order, because that ordering is an authoring decision: the
   * blueprint puts the finished Reel before the hashtags for a reason.
   */
  private static List<RunArtefact> artefacts(
      Map<String, Object> values, List<BlueprintOutput> declared) {
    List<RunArtefact> artefacts = new ArrayList<>();
    for (BlueprintOutput output : declared) {
      Object value = values.get(output.key());
      if (value != null) {
        artefacts.add(
            new RunArtefact(output.key(), output.label(), output.type(), String.valueOf(value)));
      }
    }
    // Anything the run produced that the blueprint no longer declares still belongs to the actor.
    values.entrySet().stream()
        .filter(entry -> declared.stream().noneMatch(output -> output.key().equals(entry.getKey())))
        .forEach(
            entry ->
                artefacts.add(
                    new RunArtefact(entry.getKey(), null, null, String.valueOf(entry.getValue()))));
    return List.copyOf(artefacts);
  }

  /** A run row before its outputs are resolved against the blueprint. */
  private record RawRun(RunDetail run, Map<String, Object> values) {}

  private RawRun readRun(ResultSet rs) throws SQLException {
    Map<String, Object> values = columns.objectMap(rs.getString("outputs"));
    RunDetail run =
        new RunDetail(
            rs.getObject("id", UUID.class),
            rs.getObject("installation_id", UUID.class),
            rs.getString("studio_key"),
            rs.getString("blueprint_version"),
            RunStatus.valueOf(rs.getString("status")),
            rs.getString("hold_id"),
            List.of(),
            rs.getString("error_message"),
            columns.instantAt(rs, "started_at"),
            columns.instantAt(rs, "finished_at"),
            List.of());
    return new RawRun(run, values);
  }
}
