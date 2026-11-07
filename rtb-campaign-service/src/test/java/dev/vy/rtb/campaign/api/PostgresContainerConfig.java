package dev.vy.rtb.campaign.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One PostgreSQL container for the whole API test suite; Spring wires the datasource to it. */
@TestConfiguration(proxyBeanMethods = false)
class PostgresContainerConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:16-alpine");
  }
}
