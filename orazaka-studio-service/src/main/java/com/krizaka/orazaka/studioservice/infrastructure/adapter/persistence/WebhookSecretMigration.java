package com.krizaka.orazaka.studioservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.studioservice.domain.port.WebhookSecretSealer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seals the signing secrets that were stored in the clear (before this, {@code
 * studio_webhook_endpoint.secret} held {@code whsec_...} as is). Runs once the beans exist and
 * before the webhook sweep is scheduled, so no delivery ever meets a clear secret; idempotent: a
 * row already sealed is left alone, so a second start changes nothing. It widens the two columns
 * first (a sealed value does not fit {@code VARCHAR(120)}).
 *
 * <p>It never logs a secret, only how many rows it converted.
 */
@Component
public class WebhookSecretMigration implements SmartInitializingSingleton {

  private static final Logger logger = LoggerFactory.getLogger(WebhookSecretMigration.class);

  private record Row(UUID id, String secret, String previousSecret) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final WebhookSecretSealer sealer;

  WebhookSecretMigration(
      JdbcTemplate jdbc, PlatformTransactionManager transactions, WebhookSecretSealer sealer) {
    this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate cannot be null");
    this.transaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactions, "PlatformTransactionManager cannot be null"));
    this.sealer = Objects.requireNonNull(sealer, "WebhookSecretSealer cannot be null");
  }

  @Override
  public void afterSingletonsInstantiated() {
    int converted = run();
    if (converted > 0) {
      logger.info("Sealed the signing secrets of {} webhook endpoint(s) at rest", converted);
    }
  }

  /**
   * Seals every clear secret.
   *
   * @return how many endpoints were converted
   */
  public int run() {
    widenColumns();
    Integer done =
        transaction.execute(
            status -> {
              List<Row> rows =
                  jdbc.query(
                      "SELECT id, secret, previous_secret FROM studio_webhook_endpoint"
                          + " WHERE secret NOT LIKE 'v1.%'"
                          + " OR (previous_secret IS NOT NULL AND previous_secret NOT LIKE 'v1.%')"
                          + " FOR UPDATE",
                      (rs, rowNum) ->
                          new Row(
                              rs.getObject("id", UUID.class),
                              rs.getString("secret"),
                              rs.getString("previous_secret")));
              for (Row row : rows) {
                jdbc.update(
                    "UPDATE studio_webhook_endpoint SET secret = ?, previous_secret = ?"
                        + " WHERE id = ?",
                    sealIfClear(row.id(), row.secret()),
                    row.previousSecret() == null
                        ? null
                        : sealIfClear(row.id(), row.previousSecret()),
                    row.id());
              }
              return rows.size();
            });
    return done == null ? 0 : done;
  }

  private String sealIfClear(UUID id, String stored) {
    return sealer.isSealed(stored) ? stored : sealer.seal(id, stored);
  }

  private void widenColumns() {
    Integer narrow =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = current_schema() AND table_name = 'studio_webhook_endpoint'"
                + " AND column_name IN ('secret', 'previous_secret')"
                + " AND character_maximum_length IS NOT NULL",
            Integer.class);
    if (narrow != null && narrow > 0) {
      jdbc.execute(
          "ALTER TABLE studio_webhook_endpoint ALTER COLUMN secret TYPE TEXT,"
              + " ALTER COLUMN previous_secret TYPE TEXT");
    }
  }
}
