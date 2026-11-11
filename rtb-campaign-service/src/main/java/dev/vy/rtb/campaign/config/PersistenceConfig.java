package dev.vy.rtb.campaign.config;

import dev.vy.rtb.campaign.outbox.OutboxCampaignRepository;
import dev.vy.rtb.campaign.outbox.OutboxCreativeRepository;
import dev.vy.rtb.campaign.outbox.OutboxRecorder;
import dev.vy.rtb.campaign.outbox.OutboxStore;
import dev.vy.rtb.campaign.persistence.CampaignRepository;
import dev.vy.rtb.campaign.persistence.CreativeRepository;
import dev.vy.rtb.campaign.persistence.JdbcCampaignRepository;
import dev.vy.rtb.campaign.persistence.JdbcCreativeRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * The repositories the application uses record every write in the outbox. The plain JDBC ones are
 * built here and only handed to the recording wrappers, so nothing can write without an event.
 */
@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  OutboxStore outboxStore(JdbcClient jdbc) {
    return new OutboxStore(jdbc);
  }

  @Bean
  CampaignRepository campaignRepository(
      JdbcClient jdbc,
      TransactionOperations transactions,
      Clock clock,
      OutboxStore outbox,
      JsonMapper json) {
    JdbcCampaignRepository plainCampaigns = new JdbcCampaignRepository(jdbc, transactions, clock);
    JdbcCreativeRepository plainCreatives = new JdbcCreativeRepository(jdbc, transactions, clock);
    OutboxRecorder recorder =
        new OutboxRecorder(plainCampaigns, plainCreatives, outbox, json, clock);
    return new OutboxCampaignRepository(plainCampaigns, recorder, transactions);
  }

  @Bean
  CreativeRepository creativeRepository(
      JdbcClient jdbc,
      TransactionOperations transactions,
      Clock clock,
      OutboxStore outbox,
      JsonMapper json) {
    JdbcCampaignRepository plainCampaigns = new JdbcCampaignRepository(jdbc, transactions, clock);
    JdbcCreativeRepository plainCreatives = new JdbcCreativeRepository(jdbc, transactions, clock);
    OutboxRecorder recorder =
        new OutboxRecorder(plainCampaigns, plainCreatives, outbox, json, clock);
    return new OutboxCreativeRepository(plainCreatives, plainCampaigns, recorder, transactions);
  }
}
