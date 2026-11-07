package dev.vy.rtb.campaign.config;

import dev.vy.rtb.campaign.persistence.CampaignRepository;
import dev.vy.rtb.campaign.persistence.JdbcCampaignRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  CampaignRepository campaignRepository(
      JdbcClient jdbc, TransactionOperations transactions, Clock clock) {
    return new JdbcCampaignRepository(jdbc, transactions, clock);
  }
}
