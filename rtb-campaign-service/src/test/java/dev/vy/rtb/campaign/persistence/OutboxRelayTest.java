package dev.vy.rtb.campaign.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vy.rtb.campaign.outbox.OutboxRelay;
import dev.vy.rtb.campaign.outbox.OutboxStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seam: the relay between the outbox table and a Kafka producer (a mock here, a real one next). */
class OutboxRelayTest {

  private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

  private JdbcClient jdbc;
  private TransactionTemplate transactions;
  private OutboxStore store;
  private MockProducer<String, String> producer;

  @BeforeEach
  void setUp() {
    DataSource dataSource = PostgresTestSupport.dataSource();
    PostgresTestSupport.emptyTables();
    jdbc = JdbcClient.create(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    store = new OutboxStore(jdbc);
    producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
  }

  private OutboxRelay relay(MockProducer<String, String> with, int batch) {
    return new OutboxRelay(store, jdbc, transactions, with, "campaign-updates", batch);
  }

  private int remaining() {
    return jdbc.sql("SELECT count(*) FROM outbox").query(Integer.class).single();
  }

  @Test
  void publishesRowsInWriteOrderKeyedByCampaignAndThenDeletesThem() {
    store.append(A, "{\"n\":1}");
    store.append(B, "{\"n\":2}");
    store.append(A, "{\"n\":3}");

    int sent = relay(producer, 100).runOnce();

    assertThat(sent).isEqualTo(3);
    List<ProducerRecord<String, String>> history = producer.history();
    assertThat(history)
        .extracting(ProducerRecord::key)
        .containsExactly(A.toString(), B.toString(), A.toString());
    assertThat(history)
        .extracting(ProducerRecord::value)
        .containsExactly("{\"n\":1}", "{\"n\":2}", "{\"n\":3}");
    assertThat(history).extracting(ProducerRecord::topic).containsOnly("campaign-updates");
    assertThat(remaining()).isZero();
  }

  @Test
  void aTombstoneIsSentAsANullValue() {
    store.append(A, null);

    relay(producer, 100).runOnce();

    assertThat(producer.history()).hasSize(1);
    assertThat(producer.history().get(0).value()).isNull();
    assertThat(producer.history().get(0).key()).isEqualTo(A.toString());
  }

  @Test
  void oneRunSendsAtMostABatch() {
    for (int i = 0; i < 3; i++) {
      store.append(A, "{\"n\":" + i + "}");
    }
    OutboxRelay relay = relay(producer, 2);

    assertThat(relay.runOnce()).isEqualTo(2);
    assertThat(remaining()).isEqualTo(1);
    assertThat(relay.runOnce()).isEqualTo(1);
    assertThat(relay.runOnce()).isZero();
  }

  @Test
  void whenKafkaRefusesNothingIsDeletedAndTheNextRunTriesAgain() {
    store.append(A, "{\"n\":1}");
    store.append(B, "{\"n\":2}");
    MockProducer<String, String> down =
        new MockProducer<>(true, new StringSerializer(), new StringSerializer()) {
          @Override
          public java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(
              ProducerRecord<String, String> record) {
            throw new KafkaException("broker is down");
          }
        };

    assertThatThrownBy(() -> relay(down, 100).runOnce()).isInstanceOf(KafkaException.class);

    assertThat(remaining()).isEqualTo(2);
    assertThat(relay(producer, 100).runOnce()).isEqualTo(2); // Kafka is back
    assertThat(remaining()).isZero();
  }

  @Test
  void onlyOneInstanceRelaysAtATime() throws Exception {
    store.append(A, "{\"n\":1}");
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread leader =
        Thread.ofVirtual()
            .start(
                () ->
                    transactions.executeWithoutResult(
                        tx -> {
                          jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                              .param("key", OutboxRelay.LOCK_KEY)
                              .query()
                              .listOfRows();
                          locked.countDown();
                          try {
                            release.await(10, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    int sentWhileAnotherInstanceLeads = relay(producer, 100).runOnce();

    release.countDown();
    leader.join();
    assertThat(sentWhileAnotherInstanceLeads).isZero();
    assertThat(producer.history()).isEmpty();
    assertThat(remaining()).isEqualTo(1);
    assertThat(relay(producer, 100).runOnce()).isEqualTo(1); // the lock is free again
  }
}
