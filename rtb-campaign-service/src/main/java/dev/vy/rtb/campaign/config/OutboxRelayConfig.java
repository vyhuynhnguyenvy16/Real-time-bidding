package dev.vy.rtb.campaign.config;

import dev.vy.rtb.campaign.outbox.OutboxRelay;
import dev.vy.rtb.campaign.outbox.OutboxRelayScheduler;
import dev.vy.rtb.campaign.outbox.OutboxStore;
import dev.vy.rtb.campaign.outbox.TopicSetup;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The relay is off unless {@code rtb.outbox.relay.enabled=true}, so tests and tools need no Kafka.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "rtb.outbox.relay.enabled", havingValue = "true")
class OutboxRelayConfig {

  @Bean(destroyMethod = "close")
  KafkaProducer<String, String> outboxProducer(
      @Value("${rtb.outbox.relay.bootstrap-servers}") String bootstrapServers) {
    return new KafkaProducer<>(OutboxRelay.producerProperties(bootstrapServers));
  }

  @Bean
  OutboxRelay outboxRelay(
      OutboxStore store,
      JdbcClient jdbc,
      TransactionOperations transactions,
      KafkaProducer<String, String> producer,
      @Value("${rtb.outbox.relay.topic:campaign-updates}") String topic,
      @Value("${rtb.outbox.relay.batch-size:100}") int batchSize) {
    return new OutboxRelay(store, jdbc, transactions, producer, topic, batchSize);
  }

  @Bean
  OutboxRelayScheduler outboxRelayScheduler(
      OutboxRelay relay,
      @Value("${rtb.outbox.relay.bootstrap-servers}") String bootstrapServers,
      @Value("${rtb.outbox.relay.topic:campaign-updates}") String topic,
      @Value("${rtb.outbox.relay.partitions:3}") int partitions,
      @Value("${rtb.outbox.relay.replication-factor:1}") short replicas) {
    return new OutboxRelayScheduler(
        relay, () -> TopicSetup.ensure(bootstrapServers, topic, partitions, replicas));
  }
}
