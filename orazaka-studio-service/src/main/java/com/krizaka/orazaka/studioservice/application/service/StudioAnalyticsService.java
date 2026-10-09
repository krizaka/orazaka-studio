package com.krizaka.orazaka.studioservice.application.service;

import com.krizaka.orazaka.studio.domain.model.InstallationStatus;
import com.krizaka.orazaka.studio.domain.model.RunStatus;
import com.krizaka.orazaka.studioservice.domain.model.StudioUsage;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What the marketplace is actually doing (design §14).
 *
 * <p>One query over the whole catalogue rather than a count per Studio: the console renders every
 * row at once, and a per-row lookup would be an N+1 across a page whose whole purpose is comparison
 * (ERR-109).
 *
 * <p>Left joins, deliberately: a Studio nobody installed is the most interesting row on the screen,
 * and an inner join would hide exactly the products that are not working.
 *
 * <p><b>Runs of a SENSITIVE pack are excluded, and the exclusion is in the SQL rather than in the
 * caller</b> (ADR-051). "Never exported to analytics" has to hold for every reader of this service,
 * present and future, and a filter applied by whoever renders the page holds only for the page that
 * exists today. A SENSITIVE Studio therefore shows zero runs here — which is the correct answer to
 * a question this screen is not allowed to ask, not a gap.
 *
 * <p>The literal {@code 'STANDARD'} is deliberately positive: filtering {@code <> 'SENSITIVE'}
 * would silently start counting a class added later, and the next class added is the one with the
 * heaviest obligations.
 */
@Service
public class StudioAnalyticsService {

  private static final String USAGE_QUERY =
      """
      SELECT s.studio_key,
             s.label,
             COALESCE(i.installations, 0)  AS installations,
             COALESCE(r.runs, 0)           AS runs,
             COALESCE(r.succeeded, 0)      AS succeeded,
             COALESCE(r.failed, 0)         AS failed,
             COALESCE(b.estimated_credits, 0) AS estimated_credits
        FROM studio s
        LEFT JOIN (SELECT studio_key, count(*) AS installations
                     FROM studio_installation WHERE status <> ? GROUP BY studio_key) i
               ON i.studio_key = s.studio_key
        LEFT JOIN (SELECT studio_key,
                          count(*)                                   AS runs,
                          count(*) FILTER (WHERE status = ?)         AS succeeded,
                          count(*) FILTER (WHERE status = ?)         AS failed
                     FROM studio_run
                    WHERE data_class = 'STANDARD'
                    GROUP BY studio_key) r
               ON r.studio_key = s.studio_key
        LEFT JOIN studio_blueprint b
               ON b.studio_key = s.studio_key AND b.version = s.latest_version
       ORDER BY COALESCE(r.runs, 0) DESC, s.label
      """;

  private final JdbcTemplate jdbcTemplate;

  public StudioAnalyticsService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * Usage across the whole catalogue, busiest first.
   *
   * @return one row per Studio, including the ones nobody has touched
   */
  public List<StudioUsage> usage() {
    return jdbcTemplate.query(
        USAGE_QUERY,
        (rs, rowNum) ->
            new StudioUsage(
                rs.getString("studio_key"),
                rs.getString("label"),
                rs.getLong("installations"),
                rs.getLong("runs"),
                rs.getLong("succeeded"),
                rs.getLong("failed"),
                rs.getLong("estimated_credits")),
        InstallationStatus.REVOKED.name(),
        RunStatus.SUCCEEDED.name(),
        RunStatus.FAILED.name());
  }
}
