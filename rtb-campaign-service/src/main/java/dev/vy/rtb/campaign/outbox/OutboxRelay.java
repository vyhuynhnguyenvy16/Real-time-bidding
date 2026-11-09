package dev.vy.rtb.campaign.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Publishes outbox rows to Kafka (docs/adr/0003). One batch per {@link #runOnce()}, oldest first.
 *
 * <p>Delivery is at least once: rows are deleted only after Kafka has acknowledged every record of
 * the batch, so a crash or a failure in between sends some records again. Consumers drop repeats
 * (an event carries its campaign version). Only one instance relays at a time: the whole batch runs
 * in a transaction that holds a PostgreSQL advisory lock, and an instance that cannot get it does
 * nothing. If the broker is down the exception rolls the transaction back and the rows stay put, so
 * the API keeps working and the backlog drains when Kafka returns.
 */
public final class OutboxRelay {

  /** Arbitrary but fixed: every instance of the service must use the same lock key. */
  public static final long LOCK_KEY = 0x52_54_42_4F_55_54_42_58L; // "RTBOUTBX"

  private static final long ACK_TIMEOUT_SECONDS = 10;

  private final OutboxStore store;
  private final JdbcClient jdbc;
  private final TransactionOperations transactions;
  private final Producer<String, String> producer;
  private final String topic;
  private final int batchSize;

  public OutboxRelay(
      OutboxStore store,
      JdbcClient jdbc,
      TransactionOperations transactions,
      Producer<String, String> producer,
      String topic,
      int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1");
    }
    this.store = store;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.producer = producer;
    this.topic = topic;
    this.batchSize = batchSize;
  }

  /** Producer settings that make a record either durable and in order, or an error. */
  public static Map<String, Object> producerProperties(String bootstrapServers) {
    return Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
        bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
        StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
        StringSerializer.class,
        ProducerConfig.ACKS_CONFIG,
        "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
        true,
        ProducerConfig.MAX_BLOCK_MS_CONFIG,
        5_000,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
        5_000,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
        10_000);
  }

  /**
   * Sends one batch.
   *
   * @return how many rows were published; 0 if there was nothing to do or another instance leads
   * @throws KafkaException (or a runtime exception) if Kafka did not acknowledge; nothing is
   *     deleted
   */
  public int runOnce() {
    Integer sent = transactions.execute(tx -> relayBatch());
    return sent == null ? 0 : sent;
  }

  private int relayBatch() {
    Boolean leader =
        jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
            .param("key", LOCK_KEY)
            .query(Boolean.class)
            .single();
    if (!Boolean.TRUE.equals(leader)) {
      return 0;
    }
    List<OutboxStore.Row> rows = store.oldest(batchSize);
    if (rows.isEmpty()) {
      return 0;
    }
    List<Future<RecordMetadata>> acks = new ArrayList<>(rows.size());
    for (OutboxStore.Row row : rows) {
      acks.add(
          producer.send(new ProducerRecord<>(topic, row.campaignId().toString(), row.payload())));
    }
    producer.flush();
    for (Future<RecordMetadata> ack : acks) {
      await(ack);
    }
    store.delete(rows.stream().map(OutboxStore.Row::id).toList());
    return rows.size();
  }

  private static void await(Future<RecordMetadata> ack) {
    try {
      ack.get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new KafkaException("Interrupted while waiting for Kafka", e);
    } catch (Exception e) {
      throw new KafkaException("Kafka did not acknowledge an outbox record", e);
    }
  }
}
