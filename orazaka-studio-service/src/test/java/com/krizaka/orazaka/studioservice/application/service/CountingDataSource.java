package com.krizaka.orazaka.studioservice.application.service;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} decorator that records every SQL statement actually executed through it.
 *
 * <p>Phase M0's instrument for §4.3 — the claim that a Studio run costs <i>"one {@code studio_run}
 * + one {@code studio_run_step} insert plus an advance cycle"</i> is a claim about statements, and
 * counting them is the only way to check it. Test scope only: nothing in {@code src/main} learns
 * that it is being measured, which is the point — a measurement that changes the code path measures
 * the instrument.
 *
 * <p>Counted at {@code execute*} on the statement rather than at {@code prepareStatement} on the
 * connection, because a prepared statement that is never executed costs the database nothing, and
 * because {@code JdbcTemplate} prepares and executes in the same breath either way. {@code
 * executeBatch} counts as <b>one</b> — the batch is one round-trip, and reporting it as its member
 * count would flatter nothing but would misdescribe what Postgres was asked to do.
 */
final class CountingDataSource implements DataSource {

  private final DataSource delegate;
  private final List<String> recorded = Collections.synchronizedList(new ArrayList<>());
  private volatile boolean recording;

  CountingDataSource(DataSource delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate DataSource required");
  }

  /** Begins a recording window, discarding whatever the previous one held. */
  void begin() {
    recorded.clear();
    recording = true;
  }

  /**
   * Ends the recording window.
   *
   * @return the SQL executed while it was open, in execution order
   */
  List<String> end() {
    recording = false;
    synchronized (recorded) {
      return recorded.stream().map(sql -> sql.replaceAll("\\s+", " ").trim()).toList();
    }
  }

  /**
   * Captures the statement raw.
   *
   * <p>Normalisation is deferred to {@link #end()} so that recording costs a volatile read and a
   * list append — the same harness times the run with recording off, and an instrument that costs a
   * regular expression per statement would be measuring itself.
   */
  private void record(String sql) {
    if (recording) {
      recorded.add(sql == null ? "<unknown>" : sql);
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    return proxy(delegate.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return proxy(delegate.getConnection(username, password));
  }

  private Connection proxy(Connection real) {
    return (Connection)
        Proxy.newProxyInstance(
            CountingDataSource.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Object result = call(method, real, args);
              if (result instanceof Statement statement) {
                String sql =
                    args != null && args.length > 0 && args[0] instanceof String prepared
                        ? prepared
                        : null;
                return proxyStatement(statement, sql);
              }
              return result;
            });
  }

  private Object proxyStatement(Statement real, String preparedSql) {
    Class<?> contract =
        real instanceof CallableStatement
            ? CallableStatement.class
            : real instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    return Proxy.newProxyInstance(
        CountingDataSource.class.getClassLoader(),
        new Class<?>[] {contract},
        (proxy, method, args) -> {
          if (method.getName().startsWith("execute")) {
            String sql =
                args != null && args.length > 0 && args[0] instanceof String inline
                    ? inline
                    : preparedSql;
            record(sql);
          }
          return call(method, real, args);
        });
  }

  private static Object call(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException wrapped) {
      throw wrapped.getCause() == null ? wrapped : wrapped.getCause();
    }
  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    delegate.setLogWriter(out);
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    delegate.setLoginTimeout(seconds);
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getGlobal();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isInstance(this) || delegate.isWrapperFor(iface);
  }
}
