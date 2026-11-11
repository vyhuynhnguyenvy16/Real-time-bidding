package dev.vy.rtb.campaign.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vy.rtb.campaign.outbox.OutboxRelay;
import dev.vy.rtb.campaign.outbox.OutboxStore;
import dev.vy.rtb.campaign.outbox.TopicSetup;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

/** Seam: the relay against a real broker: the compacted topic, ordering, tombstones, redelivery. */
class OutboxRelayKafkaTest {

  private static final String TOPIC = "campaign-updates";

  private static KafkaContainer kafka;

  private OutboxStore store;
  private OutboxRelay relay;
  private KafkaProducer<String, String> producer;

  @BeforeAll
  static void startKafka() {
    kafka = new KafkaContainer("apache/kafka:3.9.1");
    kafka.start();
    TopicSetup.ensure(kafka.getBootstrapServers(), TOPIC, 1, (short) 1);
  }

  @AfterAll
  static void stopKafka() {
    kafka.stop();
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = PostgresTestSupport.dataSource();
    PostgresTestSupport.emptyTables();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    store = new OutboxStore(jdbc);
    producer = new KafkaProducer<>(OutboxRelay.producerProperties(kafka.getBootstrapServers()));
    relay =
        new OutboxRelay(
            store,
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            producer,
            TOPIC,
            100);
  }

  /** Records of the given campaigns only: the topic is shared by every test of this class. */
  private List<ConsumerRecord<String, String>> readAll(Set<String> keys, int expected) {
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
      consumer.subscribe(List.of(TOPIC));
      List<ConsumerRecord<String, String>> all = new ArrayList<>();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (all.size() < expected && System.nanoTime() < deadline) {
        consumer
            .poll(Duration.ofMillis(500))
            .forEach(
                r -> {
                  if (keys.contains(r.key())) {
                    all.add(r);
                  }
                });
      }
      return all;
    }
  }

  @Test
  void theTopicIsCompactedSoOnlyTheLatestEventPerCampaignIsKept() throws Exception {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      ConfigResource topic = new ConfigResource(ConfigResource.Type.TOPIC, TOPIC);
      String policy =
          admin
              .describeConfigs(List.of(topic))
              .all()
              .get()
              .get(topic)
              .get("cleanup.policy")
              .value();
      assertThat(policy).isEqualTo("compact");
    }
  }

  @Test
  void eventsArriveKeyedByCampaignInWriteOrderAndATombstoneIsANullValue() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    store.append(a, "{\"v\":1}");
    store.append(b, "{\"v\":1}");
    store.append(a, "{\"v\":2}");
    store.append(b, null);

    assertThat(relay.runOnce()).isEqualTo(4);

    List<ConsumerRecord<String, String>> read = readAll(Set.of(a.toString(), b.toString()), 4);
    assertThat(read).hasSize(4);
    assertThat(read.stream().filter(r -> r.key().equals(a.toString())).map(ConsumerRecord::value))
        .containsExactly("{\"v\":1}", "{\"v\":2}");
    List<String> forB =
        read.stream().filter(r -> r.key().equals(b.toString())).map(ConsumerRecord::value).toList();
    assertThat(forB).hasSize(2);
    assertThat(forB.get(0)).isEqualTo("{\"v\":1}");
    assertThat(forB.get(1)).isNull();
  }

  @Test
  void aSecondRunHasNothingLeftToSend() {
    store.append(UUID.randomUUID(), "{\"v\":1}");

    assertThat(relay.runOnce()).isEqualTo(1);
    assertThat(relay.runOnce()).isZero();
  }
}
