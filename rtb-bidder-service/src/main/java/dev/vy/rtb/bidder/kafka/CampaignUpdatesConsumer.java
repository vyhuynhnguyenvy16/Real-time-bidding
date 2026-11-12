package dev.vy.rtb.bidder.kafka;

import dev.vy.rtb.bidder.snapshot.SnapshotStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the {@link SnapshotStore} in step with the compacted {@code campaign-updates} topic.
 *
 * <p>Start-up reads the topic from the beginning up to the end offsets seen at that moment and only
 * then reports {@link #ready()}, so the bidder never answers from a half-loaded snapshot. After
 * that it tails the topic. It never talks to PostgreSQL and needs no consumer group: it assigns the
 * partitions itself and always starts from the beginning.
 */
public final class CampaignUpdatesConsumer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(CampaignUpdatesConsumer.class);
  private static final Duration POLL = Duration.ofMillis(200);
  private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration RETRY_PAUSE = Duration.ofSeconds(1);

  /** Give up waiting for the last offsets after this long without any record (compaction gaps). */
  private static final Duration STALL_LIMIT = Duration.ofSeconds(30);

  private final KafkaConsumer<String, String> consumer;
  private final String topic;
  private final SnapshotStore store;

  private volatile boolean ready;
  private volatile boolean running = true;
  private Thread thread;

  public CampaignUpdatesConsumer(
      KafkaConsumer<String, String> consumer, String topic, SnapshotStore store) {
    this.consumer = consumer;
    this.topic = topic;
    this.store = store;
  }

  public static Properties properties(String bootstrapServers) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
    p.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 4 * 1024 * 1024);
    return p;
  }

  /** True once the topic has been read up to its end offsets at start-up. */
  public boolean ready() {
    return ready;
  }

  /** Starts the background thread. */
  public synchronized void start() {
    if (thread != null) {
      return;
    }
    thread = Thread.ofPlatform().daemon().name("campaign-updates-consumer").unstarted(this::run);
    thread.start();
  }

  private void run() {
    try {
      List<TopicPartition> partitions = waitForPartitions();
      if (partitions == null) {
        return;
      }
      bootstrap(partitions);
      ready = true;
      LOG.info("Snapshot loaded: {} campaigns, now tailing {}", store.campaigns().size(), topic);
      while (running) {
        try {
          apply(consumer.poll(POLL));
        } catch (WakeupException e) {
          return;
        } catch (KafkaException e) {
          LOG.warn("Reading {} failed, will retry: {}", topic, e.toString());
          pause();
        }
      }
    } catch (WakeupException e) {
      // closing
    } finally {
      consumer.close();
    }
  }

  /** An empty or missing topic is a valid start: nothing to load, so the bidder is ready. */
  private List<TopicPartition> waitForPartitions() {
    while (running) {
      try {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, METADATA_TIMEOUT);
        if (infos != null && !infos.isEmpty()) {
          return infos.stream().map(i -> new TopicPartition(topic, i.partition())).toList();
        }
      } catch (WakeupException e) {
        return null;
      } catch (KafkaException e) {
        LOG.warn("Topic {} is not readable yet: {}", topic, e.toString());
      }
      if (!ready) {
        ready = true;
        LOG.info("Topic {} does not exist yet; starting with an empty snapshot", topic);
      }
      pause();
    }
    return null;
  }

  private void bootstrap(List<TopicPartition> partitions) {
    consumer.assign(partitions);
    consumer.seekToBeginning(partitions);
    Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
    long lastProgress = System.nanoTime();
    while (running && !caughtUp(partitions, end)) {
      ConsumerRecords<String, String> records = consumer.poll(POLL);
      if (records.isEmpty()) {
        if (System.nanoTime() - lastProgress > STALL_LIMIT.toNanos()) {
          LOG.warn("No records for {} while loading; going ready with what was read", STALL_LIMIT);
          return;
        }
        continue;
      }
      lastProgress = System.nanoTime();
      apply(records);
    }
  }

  private boolean caughtUp(List<TopicPartition> partitions, Map<TopicPartition, Long> end) {
    for (TopicPartition tp : partitions) {
      if (consumer.position(tp) < end.getOrDefault(tp, 0L)) {
        return false;
      }
    }
    return true;
  }

  private void apply(ConsumerRecords<String, String> records) {
    if (records.isEmpty()) {
      return;
    }
    List<SnapshotStore.Change> changes = new ArrayList<>(records.count());
    records.forEach(r -> changes.add(new SnapshotStore.Change(r.key(), r.value())));
    store.apply(changes);
  }

  private void pause() {
    try {
      Thread.sleep(RETRY_PAUSE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      running = false;
    }
  }

  @Override
  public synchronized void close() {
    running = false;
    consumer.wakeup();
    if (thread != null) {
      try {
        thread.join(5_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
