package dev.vy.rtb.campaign.outbox;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;

/** Creates the {@code campaign-updates} topic as a compacted topic (key = campaign id). */
public final class TopicSetup {

  /** Events hold up to 20 creatives of 20 KB each, escaped as JSON; see OutboxRelay. */
  static final int MAX_MESSAGE_BYTES = 2 * 1024 * 1024;

  private TopicSetup() {}

  /**
   * Creates the topic, or checks an existing one. A topic that is not compacted would drop events
   * of campaigns that never change, so a bidder bootstrapping later would not see them: that is an
   * error here, not something to ignore.
   */
  public static void ensure(String bootstrapServers, String topic, int partitions, short replicas) {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers))) {
      NewTopic wanted =
          new NewTopic(topic, partitions, replicas)
              .configs(
                  Map.of(
                      "cleanup.policy", "compact",
                      "min.compaction.lag.ms", "0",
                      "max.message.bytes", String.valueOf(MAX_MESSAGE_BYTES)));
      try {
        admin.createTopics(List.of(wanted)).all().get(10, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
        if (!(e.getCause() instanceof TopicExistsException)) {
          throw e;
        }
        requireCompacted(admin, topic);
      }
    } catch (ExecutionException e) {
      throw new IllegalStateException("Could not set up topic " + topic, e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while setting up topic " + topic, e);
    } catch (TimeoutException e) {
      throw new IllegalStateException("Timed out setting up topic " + topic, e);
    }
  }

  private static void requireCompacted(Admin admin, String topic)
      throws ExecutionException, InterruptedException, TimeoutException {
    ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
    Config config =
        admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS).get(resource);
    String policy = config.get("cleanup.policy").value();
    if (!policy.contains("compact")) {
      throw new IllegalStateException(
          "Topic " + topic + " exists with cleanup.policy=" + policy + "; it must be compact");
    }
  }
}
