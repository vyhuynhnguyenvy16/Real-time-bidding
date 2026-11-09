package dev.vy.rtb.campaign.outbox;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;

/** Creates the {@code campaign-updates} topic as a compacted topic (key = campaign id). */
public final class TopicSetup {

  private TopicSetup() {}

  /** Idempotent: an existing topic is left as it is. */
  public static void ensure(String bootstrapServers, String topic, int partitions, short replicas) {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers))) {
      NewTopic wanted =
          new NewTopic(topic, partitions, replicas)
              .configs(Map.of("cleanup.policy", "compact", "min.compaction.lag.ms", "0"));
      admin.createTopics(List.of(wanted)).all().get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (!(e.getCause() instanceof TopicExistsException)) {
        throw new IllegalStateException("Could not create topic " + topic, e.getCause());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while creating topic " + topic, e);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException("Timed out creating topic " + topic, e);
    }
  }
}
