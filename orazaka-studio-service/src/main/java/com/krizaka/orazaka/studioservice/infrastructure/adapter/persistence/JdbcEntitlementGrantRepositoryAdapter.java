package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studioservice.domain.port.EntitlementGrantRepository;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the operator's grants from {@code studio_entitlement_grant} (infra/initdb/80-studio.sql).
 *
 * <p>Package-private adapter behind {@link EntitlementGrantRepository} (AGENTS.md §2). A grant past
 * its {@code expires_at} is not in force; the database's clock decides, so every replica agrees.
 */
@Component
class JdbcEntitlementGrantRepositoryAdapter implements EntitlementGrantRepository {

  private final JdbcTemplate jdbcTemplate;

  JdbcEntitlementGrantRepositoryAdapter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  @Override
  public Set<String> grantedKeys(String actorId) {
    return Set.copyOf(
        jdbcTemplate.queryForList(
            "SELECT entitlement_key FROM studio_entitlement_grant"
                + " WHERE actor_id = ? AND (expires_at IS NULL OR expires_at > now())",
            String.class,
            actorId));
  }
}
