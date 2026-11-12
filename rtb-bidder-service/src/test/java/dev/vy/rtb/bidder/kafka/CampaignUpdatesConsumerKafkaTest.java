package dev.vy.rtb.bidder.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vy.rtb.bidder.snapshot.BidderCampaign;
import dev.vy.rtb.bidder.snapshot.SnapshotStore;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;

/** Seam: the consumer against a real broker: bootstrap, readiness, tailing, tombstones. */
class CampaignUpdatesConsumerKafkaTest {

  private static final String MASK = "1".repeat(168);
  private static final String CREATIVES =
      "[{\"id\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"c\",\"width\":300,"
          + "\"height\":250,\"markup\":\"<a>x</a>\",\"landingUrl\":\"https://example.com\"}]";

  private static KafkaContainer kafka;

  private CampaignUpdatesConsumer consumer;

  @BeforeAll
  static void startKafka() {
    kafka = new KafkaContainer("apache/kafka:3.9.1");
    kafka.start();
  }

  @AfterAll
  static void stopKafka() {
    kafka.stop();
  }

  @AfterEach
  void stopConsumer() {
    if (consumer != null) {
      consumer.close();
    }
  }

  private static String newTopic() throws Exception {
    String topic = "campaign-updates-" + UUID.randomUUID();
    try (Admin admin =
        Admin.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic(topic, 1, (short) 1).configs(Map.of("cleanup.policy", "compact"))))
          .all()
          .get();
    }
    return topic;
  }

  private static String event(UUID id, long revision, String status) {
    return "{\"schemaVersion\":1,\"revision\":"
        + revision
        + ",\"emittedAt\":\"2026-11-12T03:00:00Z\","
        + "\"campaign\":{\"id\":\""
        + id
        + "\",\"status\":\""
        + status
        + "\",\"maxBidCpmMicros\":2000000,\"bidStrategy\":{\"type\":\"FIXED_CPM\",\"cpmMicros\":1500000},"
        + "\"startAt\":\"2026-11-01T00:00:00Z\",\"endAt\":\"2026-12-01T00:00:00Z\",\"version\":1,"
        + "\"targeting\":{\"countries\":[],\"deviceTypes\":[],\"categories\":[],"
        + "\"publisherBlocklist\":[],\"scheduleMask\":\""
        + MASK
        + "\"}},"
        + "\"creatives\":"
        + CREATIVES
        + "}";
  }

  private static void send(String topic, String key, String value) throws Exception {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
      producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
    }
  }

  private CampaignUpdatesConsumer startConsumer(String topic, SnapshotStore store) {
    consumer =
        new CampaignUpdatesConsumer(
            new KafkaConsumer<>(CampaignUpdatesConsumer.properties(kafka.getBootstrapServers())),
            topic,
            store);
    consumer.start();
    return consumer;
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within 30 s");
      }
      Thread.sleep(50);
    }
  }

  @Test
  void anEmptyTopicIsReadyWithNoCampaigns() throws Exception {
    String topic = newTopic();
    SnapshotStore store = new SnapshotStore();

    startConsumer(topic, store);
    await(consumer::ready);

    assertThat(store.campaigns()).isEmpty();
  }

  @Test
  void loadsWhatIsAlreadyOnTheTopicBeforeReportingReady() throws Exception {
    String topic = newTopic();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    send(topic, a.toString(), event(a, 1, "ACTIVE"));
    send(topic, b.toString(), event(b, 1, "ACTIVE"));
    SnapshotStore store = new SnapshotStore();

    startConsumer(topic, store);
    await(consumer::ready);

    assertThat(store.campaigns()).extracting(BidderCampaign::id).containsExactlyInAnyOrder(a, b);
  }

  @Test
  void followsPauseAndTombstoneAfterStart() throws Exception {
    String topic = newTopic();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    send(topic, a.toString(), event(a, 1, "ACTIVE"));
    send(topic, b.toString(), event(b, 1, "ACTIVE"));
    SnapshotStore store = new SnapshotStore();
    startConsumer(topic, store);
    await(consumer::ready);

    send(topic, a.toString(), event(a, 2, "PAUSED"));
    await(() -> store.campaigns().size() == 1);
    send(topic, b.toString(), null);
    await(() -> store.campaigns().isEmpty());
  }

  @Test
  void aTopicThatDoesNotExistYetStartsEmptyAndPicksUpLaterCampaigns() throws Exception {
    String topic = "campaign-updates-" + UUID.randomUUID();
    SnapshotStore store = new SnapshotStore();
    startConsumer(topic, store);
    await(consumer::ready);
    assertThat(store.campaigns()).isEmpty();

    try (Admin admin =
        Admin.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
    }
    UUID a = UUID.randomUUID();
    send(topic, a.toString(), event(a, 1, "ACTIVE"));

    await(() -> store.campaigns().size() == 1);
  }
}
