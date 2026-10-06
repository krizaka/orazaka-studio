package com.orazaka.studioservice.application.service;

import com.orazaka.studio.domain.model.Blueprint;
import com.orazaka.studio.domain.model.Studio;
import com.orazaka.studioservice.domain.model.ComposerStudio;
import com.orazaka.studioservice.domain.model.StudioAccess;
import com.orazaka.studioservice.domain.port.BlueprintRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Which Studios belong in a chat composer, and what each button fills.
 *
 * <p>The chat bar's buttons used to be {@code orazaka_capabilities} rows, carrying a label, an
 * icon, a URI and a payload template — a second catalogue with a second notion of what the platform
 * can do, reachable without a run and therefore without any of the controls a run carries. They are
 * served from Studios now, and the label and icon come from the pack's own i18n, which is what
 * makes M4 a deletion of the UI-manifest columns rather than a migration of them.
 *
 * <p><b>Not every Studio belongs in a chat box.</b> A six-step prospection Studio has a form, a
 * cost estimate and a review step; it belongs on its page. The question is answered by a predicate
 * over <b>structure</b>, never over spelling — {@code key.contains("image")} is the defect phase A
 * removed:
 *
 * <blockquote>
 *
 * a Studio the actor can run <b>in one step</b>, whose input schema asks for <b>exactly one
 * thing</b>, and that thing is a string the composer is holding.
 *
 * </blockquote>
 *
 * <p>Everything else the schema declares is optional, which is the same statement from the other
 * side: the run is complete without it. That is why {@code model} — optional, resolved by the
 * capability's own catalogue when absent — does not disqualify a Studio, while a second required
 * input does: a button has one prompt and one attachment, and nowhere to ask for a third thing.
 *
 * <p><b>Why a predicate and not a flag.</b> A flag drifts: a pack author who forgets it loses a
 * button silently, and one who sets it on a four-step Studio gets a button that opens a form. The
 * structure cannot drift because it <i>is</i> the thing being asked about — a Studio that grows a
 * second step or a second required input stops being launchable from a chat bar at the moment it
 * grows it, and this recomputes.
 *
 * <p><b>What structure cannot carry is what the single input holds</b>, and the SENSITIVE pack
 * shipped here is why that matters rather than being a detail. {@code document-authenticity} is one
 * step over one required string — {@code documentBase64}, an encoded identity document. It passes
 * every structural test a chat bar could apply, and a chat bar cannot fill it: there is nothing a
 * user types that is a base64 document. An asset id, a sentence and a base64 payload are the same
 * JSON type, so the input <b>declares</b> which it is with JSON Schema's own {@code format}: {@code
 * prose} for what a user types, {@code asset-id} for what they attach. <b>An input that declares
 * neither is not filled</b> — the Studio keeps its page and loses its button, which is the failure
 * direction AGENTS.md §12's corollary asks for: a pack author outside this repository cannot be
 * asked to remember, so silence must cost a button rather than corrupt a run.
 */
@Service
public class ComposerStudioService {

  /**
   * What an input holds, declared with JSON Schema's own {@code format} keyword.
   *
   * <p>Two values, and an input that declares neither is one the composer does not fill.
   */
  private static final String ASSET_FORMAT = "asset-id";

  private static final String PROSE_FORMAT = "prose";

  private final StudioCatalogService studioCatalogService;
  private final StudioAccessService studioAccessService;
  private final BlueprintRepository blueprintRepository;
  private final ObjectMapper objectMapper;

  public ComposerStudioService(
      StudioCatalogService studioCatalogService,
      StudioAccessService studioAccessService,
      BlueprintRepository blueprintRepository,
      ObjectMapper objectMapper) {
    this.studioCatalogService =
        Objects.requireNonNull(studioCatalogService, "StudioCatalogService cannot be null");
    this.studioAccessService =
        Objects.requireNonNull(studioAccessService, "StudioAccessService cannot be null");
    this.blueprintRepository =
        Objects.requireNonNull(blueprintRepository, "BlueprintRepository cannot be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
  }

  /**
   * The composer's button row for one actor.
   *
   * <p>A Studio the actor cannot run is returned <b>locked</b> rather than dropped: the row is a
   * statement of what this platform does, and hiding a capability someone has not bought turns a
   * purchase decision into a mystery. That is what the capability row's {@code available} / {@code
   * lockedReason} pair did, and the composer renders it the same way.
   *
   * @param locale the caller's locale; a Studio without that translation falls back to its base row
   * @param actorId the authenticated caller, whose entitlements decide each button's locked state
   * @return the buttons, in catalogue order
   */
  public List<ComposerStudio> row(String locale, String actorId) {
    List<Studio> published = studioCatalogService.browse(null, locale);
    Map<String, StudioAccess> access = studioAccessService.evaluateAll(published, actorId);

    List<ComposerStudio> row = new ArrayList<>();
    for (Studio studio : published) {
      singleInput(studio)
          .ifPresent(
              input -> {
                StudioAccess decision = access.get(studio.studioKey());
                row.add(
                    new ComposerStudio(
                        studio.studioKey(),
                        studio.label(),
                        studio.iconKey(),
                        studio.latestVersion(),
                        input.capabilityKey(),
                        input.key(),
                        input.kind(),
                        input.promptKey(),
                        decision.locked(),
                        decision.reason()));
              });
    }
    return row;
  }

  /**
   * The one input a composer can fill, when this Studio has exactly one and one step.
   *
   * @return the input, or empty when this Studio does not belong in a chat bar
   */
  private Optional<SingleInput> singleInput(Studio studio) {
    if (studio.latestVersion() == null) {
      return Optional.empty();
    }
    Optional<Blueprint> blueprint =
        blueprintRepository.find(studio.studioKey(), studio.latestVersion());
    if (blueprint.isEmpty() || blueprint.get().steps().size() != 1) {
      return Optional.empty();
    }
    JsonNode schema = objectMapper.readTree(blueprint.get().inputSchema());
    JsonNode required = schema.path("required");
    if (!required.isArray() || required.size() != 1) {
      return Optional.empty();
    }
    String key = required.get(0).asString();
    JsonNode property = schema.path("properties").path(key);
    // A composer holds a sentence and an attachment, both strings on the wire. An input of any
    // other type needs a form, which is the page this Studio already has.
    if (!"string".equals(property.path("type").asString())) {
      return Optional.empty();
    }
    String format = property.path("format").asString();
    boolean holdsAnAsset = ASSET_FORMAT.equals(format);
    if (!holdsAnAsset && !PROSE_FORMAT.equals(format)) {
      // The Studio may well be runnable in one step; what it wants is not something a chat bar
      // holds, and it has not said otherwise.
      return Optional.empty();
    }
    return Optional.of(
        new SingleInput(
            blueprint.get().steps().get(0).featureKey(),
            key,
            holdsAnAsset ? ComposerStudio.InputKind.ASSET : ComposerStudio.InputKind.TEXT,
            holdsAnAsset ? proseInput(schema, key) : key));
  }

  /**
   * Where the composer's prose goes when the required input is an attachment.
   *
   * <p>A user who attaches an image and types a question means the question to be asked about the
   * image, and dropping it would be a regression the button row exists to avoid. Which input it
   * belongs in is the same declaration as above — the optional string whose {@code format} is
   * {@code prose} — so one keyword answers both halves of the question and neither is guessed.
   *
   * <p>Ambiguity resolves to nothing: two prose inputs mean the schema has not said which one a
   * chat bar fills, and choosing the first would be a guess. The run still starts, on the
   * attachment alone.
   */
  private static String proseInput(JsonNode schema, String requiredKey) {
    List<String> candidates = new ArrayList<>();
    schema
        .path("properties")
        .properties()
        .forEach(
            property -> {
              JsonNode value = property.getValue();
              if (!property.getKey().equals(requiredKey)
                  && PROSE_FORMAT.equals(value.path("format").asString())) {
                candidates.add(property.getKey());
              }
            });
    return candidates.size() == 1 ? candidates.get(0) : null;
  }

  /**
   * The one input a button fills, what it holds, the capability behind it, and where prose goes.
   */
  private record SingleInput(
      String capabilityKey, String key, ComposerStudio.InputKind kind, String promptKey) {}
}
