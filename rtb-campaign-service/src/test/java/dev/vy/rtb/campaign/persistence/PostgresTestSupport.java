package dev.vy.rtb.campaign.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One PostgreSQL container per test JVM, migrated once; tests empty the tables before each run. */
final class PostgresTestSupport {

  private static PostgreSQLContainer postgres;
  private static DataSource dataSource;

  private PostgresTestSupport() {}

  static synchronized DataSource dataSource() {
    if (dataSource == null) {
      postgres = new PostgreSQLContainer("postgres:16-alpine");
      postgres.start();
      DriverManagerDataSource ds =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
      dataSource = ds;
    }
    return dataSource;
  }

  static void emptyTables() {
    JdbcClient.create(dataSource())
        .sql("TRUNCATE outbox, creatives, campaign_targeting, campaigns, advertisers CASCADE")
        .update();
  }
}
