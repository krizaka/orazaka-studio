package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studioservice.domain.exception.ContentPolicyRefusedException;
import com.krizaka.orazaka.studioservice.domain.model.ContentDecision;
import com.krizaka.orazaka.studioservice.domain.model.ContentLabel;
import com.krizaka.orazaka.studioservice.infrastructure.support.RulesContentClassifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The content policy: a policy engine the <b>operator</b> configures, not a built-in filter.
 *
 * <p>Three layers, in this order.
 *
 * <ol>
 *   <li><b>{@code minors_risk} is always refused.</b> Not configurable, for any tenant, on any
 *       provider: it is illegal everywhere. This is the only rule the operator cannot change.
 *   <li><b>Tenant settings</b> ({@code content_policy_tenant}): per label, per actor or the
 *       install-wide default row ({@code '*'}), {@code REFUSE} or {@code ALLOW}. With no row the
 *       answer is ALLOW — a self-hosted install is permissive until its operator decides otherwise.
 *   <li><b>Provider terms</b> ({@code content_provider_policy}): the operator marks what each
 *       provider's terms allow (e.g. {@code allows_adult_content = false} for a hosted inference
 *       API whose acceptable-use policy forbids it). A prompt whose label a reached provider does
 *       not allow is {@code BLOCK}ed or {@code WARN}ed, as the operator chose. This protects the
 *       operator's provider account; it is not a moral filter.
 * </ol>
 *
 * <p>Classification is local (rules over the prompt, in this JVM — nothing is sent anywhere). Every
 * decision that carries a label is appended to {@code content_policy_audit} with the labels and the
 * rule — never the prompt. The audit write commits on its own so that a refusal, which rolls the
 * run's transaction back, still leaves its trace.
 */
@Service
public class ContentPolicyService {

  private static final Logger logger = LoggerFactory.getLogger(ContentPolicyService.class);

  static final String RULE_MINORS = "minors_risk.always";
  static final String RULE_PERMISSIVE = "permissive.default";

  private final JdbcTemplate jdbcTemplate;
  private final RulesContentClassifier classifier;
  private final TransactionTemplate ownTransaction;

  public ContentPolicyService(
      JdbcTemplate jdbcTemplate,
      RulesContentClassifier classifier,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.classifier = Objects.requireNonNull(classifier, "classifier required");
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Decides, without recording or throwing. The dry-run path.
   *
   * @param actorId the opaque actor (the tenant scope)
   * @param inputs the actor's run inputs; every string in them is classified
   * @param providers the capabilities (providers) the run would reach
   * @return the decision
   */
  public ContentDecision evaluate(
      String actorId, Map<String, Object> inputs, List<String> providers) {
    String text = textOf(inputs);
    Set<ContentLabel> labels = classifier.classify(text, operatorRules());
    if (labels.contains(ContentLabel.MINORS_RISK)) {
      return new ContentDecision(labels, true, RULE_MINORS, List.of(), providers);
    }
    Map<ContentLabel, String> tenant = tenantActions(actorId);
    for (ContentLabel label : labels) {
      if ("REFUSE".equals(tenant.get(label))) {
        return new ContentDecision(
            labels, true, "tenant.refuse." + label.name(), List.of(), providers);
      }
    }
    List<String> warnings = new ArrayList<>();
    for (String provider : providers) {
      Map<String, Object> row = providerRow(provider);
      if (row == null) {
        continue;
      }
      for (ContentLabel label : labels) {
        String flag = flagOf(label);
        if (flag != null && Boolean.FALSE.equals(row.get(flag))) {
          String rule = "provider." + provider + "." + flag;
          if ("WARN".equals(row.get("enforcement"))) {
            warnings.add(rule);
          } else {
            return new ContentDecision(labels, true, rule, List.of(), providers);
          }
        }
      }
    }
    return new ContentDecision(labels, false, RULE_PERMISSIVE, warnings, providers);
  }

  /**
   * Decides and records; throws when the decision refuses. Called before any hold is taken, any row
   * written or any provider reached.
   *
   * @throws ContentPolicyRefusedException when the run must not start
   */
  public ContentDecision enforce(
      String actorId, String studioKey, Map<String, Object> inputs, List<String> providers) {
    ContentDecision decision = evaluate(actorId, inputs, providers);
    record(actorId, studioKey, decision, false);
    decision
        .warnings()
        .forEach(w -> logger.warn("Content policy warning ({}) for studio {}", w, studioKey));
    if (decision.refused()) {
      logger.info("Content policy refused studio {} run: {}", studioKey, decision.rule());
      throw new ContentPolicyRefusedException(decision);
    }
    return decision;
  }

  /** Dry-run: decides and records it as a dry run; never throws, never starts anything. */
  public ContentDecision check(
      String actorId, String studioKey, Map<String, Object> inputs, List<String> providers) {
    ContentDecision decision = evaluate(actorId, inputs, providers);
    record(actorId, studioKey, decision, true);
    return decision;
  }

  void record(String actorId, String studioKey, ContentDecision decision, boolean dryRun) {
    if (decision.labels().equals(Set.of(ContentLabel.NONE))) {
      return;
    }
    try {
      ownTransaction.executeWithoutResult(
          status -> insertAudit(actorId, studioKey, decision, dryRun));
    } catch (RuntimeException e) {
      // A refusal is enforced even when its trace cannot be written; the failure is loud.
      logger.error("Could not write the content policy audit for studio {}", studioKey, e);
    }
  }

  private void insertAudit(
      String actorId, String studioKey, ContentDecision decision, boolean dryRun) {
    {
      jdbcTemplate.update(
          "INSERT INTO content_policy_audit (actor_id, studio_key, labels, decision, rule,"
              + " providers, dry_run) VALUES (?, ?, ?, ?, ?, ?, ?)",
          actorId,
          studioKey,
          decision.labels().stream()
              .map(Enum::name)
              .sorted()
              .reduce((a, b) -> a + "," + b)
              .orElse(""),
          decision.verb(),
          decision.rule(),
          String.join(",", decision.providers()),
          dryRun);
    }
  }

  private Map<ContentLabel, String> tenantActions(String actorId) {
    Map<ContentLabel, String> byLabel = new EnumMap<>(ContentLabel.class);
    // '*' first, then the actor's own row overrides it.
    jdbcTemplate.query(
        "SELECT label, action FROM content_policy_tenant WHERE subject IN ('*', ?)"
            + " ORDER BY (subject = '*') DESC",
        rs -> {
          byLabel.put(ContentLabel.valueOf(rs.getString("label")), rs.getString("action"));
        },
        actorId);
    return byLabel;
  }

  private Map<String, Object> providerRow(String provider) {
    return jdbcTemplate
        .queryForList(
            "SELECT allows_adult_content, allows_graphic_violence, allows_real_person_likeness,"
                + " enforcement FROM content_provider_policy WHERE provider_key = ?",
            provider)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Map<ContentLabel, List<Pattern>> operatorRules() {
    Map<ContentLabel, List<Pattern>> rules = new EnumMap<>(ContentLabel.class);
    jdbcTemplate.query(
        "SELECT label, pattern FROM content_policy_rule",
        rs -> {
          try {
            rules
                .computeIfAbsent(
                    ContentLabel.valueOf(rs.getString("label")), k -> new ArrayList<>())
                .add(Pattern.compile(rs.getString("pattern"), Pattern.CASE_INSENSITIVE));
          } catch (IllegalArgumentException bad) {
            logger.warn("Ignoring an unusable content_policy_rule row", bad);
          }
        });
    return rules;
  }

  private static String flagOf(ContentLabel label) {
    return switch (label) {
      case SEXUAL_EXPLICIT, SEXUAL_SUGGESTIVE -> "allows_adult_content";
      case VIOLENCE_GRAPHIC -> "allows_graphic_violence";
      case REAL_PERSON_LIKENESS -> "allows_real_person_likeness";
      default -> null;
    };
  }

  /** Every string in the inputs, nested values included. */
  static String textOf(Object value) {
    Set<String> parts = new LinkedHashSet<>();
    collect(value, parts);
    return String.join("\n", parts);
  }

  private static void collect(Object value, Set<String> out) {
    if (value instanceof CharSequence s) {
      out.add(s.toString());
    } else if (value instanceof Map<?, ?> m) {
      m.values().forEach(v -> collect(v, out));
    } else if (value instanceof Iterable<?> it) {
      it.forEach(v -> collect(v, out));
    }
  }
}
