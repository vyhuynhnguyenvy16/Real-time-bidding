package dev.vy.rtb.bidder.config;

import dev.vy.rtb.bidder.kafka.CampaignUpdatesConsumer;
import dev.vy.rtb.bidder.snapshot.SnapshotStore;
import java.time.Clock;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BidderConfig {

  @Bean
  SnapshotStore snapshotStore() {
    return new SnapshotStore();
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * On by default; tests and tools that need no Kafka set {@code rtb.bidder.kafka.enabled=false}.
   */
  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnProperty(
      name = "rtb.bidder.kafka.enabled",
      havingValue = "true",
      matchIfMissing = true)
  CampaignUpdatesConsumer campaignUpdatesConsumer(
      SnapshotStore store,
      @Value("${rtb.bidder.kafka.bootstrap-servers}") String bootstrapServers,
      @Value("${rtb.bidder.kafka.topic}") String topic) {
    return new CampaignUpdatesConsumer(
        new KafkaConsumer<>(CampaignUpdatesConsumer.properties(bootstrapServers)), topic, store);
  }
}
