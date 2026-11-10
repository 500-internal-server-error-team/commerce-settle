package com.commercesettle.commerce.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** JDBC 직접 호출과 JPA 실행을 함께 기록한다. 측정 중인 전용 테스트 컨텍스트에만 설치한다. */
public final class JdbcExecutionRecorder implements BeanPostProcessor {
  private final Queue<Execution> executions = new ConcurrentLinkedQueue<>();
  private final Queue<Long> acquisitions = new ConcurrentLinkedQueue<>();
  private volatile boolean recording;

  public <T> Sample<T> measure(Supplier<T> action) {
    executions.clear();
    acquisitions.clear();
    recording = true;
    try {
      long started = System.nanoTime();
      T result = action.get();
      return new Sample<>(
          result, System.nanoTime() - started, List.copyOf(executions), List.copyOf(acquisitions));
    } finally {
      recording = false;
    }
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String name) {
    if (!(bean instanceof DataSource source)) return bean;
    return new DelegatingDataSource(source) {
      @Override
      public Connection getConnection() throws java.sql.SQLException {
        long started = System.nanoTime();
        Connection connection = source.getConnection();
        if (recording) acquisitions.add(System.nanoTime() - started);
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  Object value = invoke(connection, method, args);
                  if (!(value instanceof Statement statement)) return value;
                  String prepared = args != null && args.length > 0 ? (String) args[0] : null;
                  Class<?> type =
                      statement instanceof PreparedStatement
                          ? PreparedStatement.class
                          : Statement.class;
                  return Proxy.newProxyInstance(
                      type.getClassLoader(),
                      new Class<?>[] {type},
                      (p, operation, parameters) -> {
                        if (!recording || !operation.getName().startsWith("execute"))
                          return invoke(statement, operation, parameters);
                        String sql = prepared != null ? prepared : String.valueOf(parameters[0]);
                        long began = System.nanoTime();
                        try {
                          return invoke(statement, operation, parameters);
                        } finally {
                          executions.add(
                              new Execution(
                                  sql.replaceAll("\\s+", " ").strip(), System.nanoTime() - began));
                        }
                      });
                });
      }
    };
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }

  public record Execution(String sql, long nanos) {}

  public record Sample<T>(
      T result, long nanos, List<Execution> executions, List<Long> acquisitions) {
    public long inventoryLocks() {
      return executions.stream()
          .filter(
              e ->
                  e.sql().contains("from commerce.product_inventory")
                      && e.sql().contains("for no key update"))
          .count();
    }

    public long timeoutSettings() {
      return executions.stream().filter(e -> e.sql().contains("set_config(budget.name")).count();
    }
  }
}
