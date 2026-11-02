package com.commercesettle.settlement.infrastructure.batch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * batchDate별 일배치 실행자 잠금. PostgreSQL 세션 advisory lock을 전용 연결에 잡고 Job이 끝날 때까지 쥔다. 실행 프로세스가 죽어 연결이 끊기면
 * DB가 잠금을 풀어 주므로, 잠금을 잡을 수 있다는 것은 그 batchDate를 실행 중인 실행자가 없다는 뜻이다(ADR 0007 D7).
 */
@Component
class DailySettlementLock {
  // 일배치 전용 잠금 공간. 두 번째 키는 batchDate의 epoch day다.
  static final int NAMESPACE = 0x5E77_1E01;

  private final DataSource dataSource;

  DailySettlementLock(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** 잠금을 잡으면 풀 때 닫을 핸들을, 다른 연결이 쥐고 있으면 빈 값을 돌려준다. */
  Optional<Held> tryAcquire(LocalDate batchDate) {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      if (call(connection, "SELECT pg_try_advisory_lock(?, ?)", batchDate)) {
        return Optional.of(new Held(connection, batchDate));
      }
      connection.close();
      return Optional.empty();
    } catch (SQLException e) {
      closeQuietly(connection, e);
      throw new DataAccessResourceFailureException("daily settlement lock is unavailable", e);
    }
  }

  static int key(LocalDate batchDate) {
    return Math.toIntExact(batchDate.toEpochDay());
  }

  private static boolean call(Connection connection, String sql, LocalDate batchDate)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, NAMESPACE);
      statement.setInt(2, key(batchDate));
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private static void closeQuietly(Connection connection, Exception cause) {
    if (connection == null) return;
    try {
      connection.close();
    } catch (SQLException e) {
      cause.addSuppressed(e);
    }
  }

  /** 풀로 돌려주기 전에 잠금을 푼다. 연결이 이미 끊겼으면 DB가 잠금을 풀었으므로 닫기만 한다. */
  static final class Held implements AutoCloseable {
    private final Connection connection;
    private final LocalDate batchDate;

    private Held(Connection connection, LocalDate batchDate) {
      this.connection = connection;
      this.batchDate = batchDate;
    }

    @Override
    public void close() {
      try (connection) {
        call(connection, "SELECT pg_advisory_unlock(?, ?)", batchDate);
      } catch (SQLException e) {
        throw new DataAccessResourceFailureException("daily settlement lock release failed", e);
      }
    }
  }
}
