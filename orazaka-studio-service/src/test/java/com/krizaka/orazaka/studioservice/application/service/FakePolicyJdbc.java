package com.krizaka.orazaka.studioservice.application.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** An in-memory stand-in for the three policy tables, so decisions can be tested as a table. */
class FakePolicyJdbc extends JdbcTemplate {

  /** subject, label, action */
  final List<String[]> tenant = new ArrayList<>();

  /** provider_key -> columns */
  final Map<String, Map<String, Object>> providers = new java.util.HashMap<>();

  /** label, pattern */
  final List<String[]> rules = new ArrayList<>();

  final List<Object[]> audit = new ArrayList<>();

  FakePolicyJdbc tenant(String subject, String label, String action) {
    tenant.add(new String[] {subject, label, action});
    return this;
  }

  FakePolicyJdbc provider(String key, boolean adult, String enforcement) {
    providers.put(
        key,
        Map.of(
            "allows_adult_content",
            adult,
            "allows_graphic_violence",
            true,
            "allows_real_person_likeness",
            true,
            "enforcement",
            enforcement));
    return this;
  }

  @Override
  public void query(String sql, RowCallbackHandler handler) {
    feed(sql, handler, null);
  }

  @Override
  public void query(String sql, RowCallbackHandler handler, Object... args) {
    feed(sql, handler, args.length > 0 ? String.valueOf(args[0]) : null);
  }

  private void feed(String sql, RowCallbackHandler handler, String actor) {
    try {
      if (sql.contains("content_policy_tenant")) {
        // '*' rows first, then the actor's own, as the SQL orders them.
        for (String[] row : tenant) {
          if (row[0].equals("*")) {
            handler.processRow(rs(Map.of("label", row[1], "action", row[2])));
          }
        }
        for (String[] row : tenant) {
          if (!row[0].equals("*") && row[0].equals(actor)) {
            handler.processRow(rs(Map.of("label", row[1], "action", row[2])));
          }
        }
      } else if (sql.contains("content_policy_rule")) {
        for (String[] row : rules) {
          handler.processRow(rs(Map.of("label", row[0], "pattern", row[1])));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public List<Map<String, Object>> queryForList(String sql, Object... args) {
    Map<String, Object> row = providers.get(String.valueOf(args[0]));
    return row == null ? List.of() : List.of(row);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T queryForObject(String sql, Class<T> type, Object... args) {
    // enforceConcurrencyCap: no run is active
    return (T) Integer.valueOf(0);
  }

  @Override
  public int update(String sql, Object... args) {
    audit.add(args);
    return 1;
  }

  private static ResultSet rs(Map<String, String> values) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    for (var e : values.entrySet()) {
      when(rs.getString(e.getKey())).thenReturn(e.getValue());
    }
    return rs;
  }
}
