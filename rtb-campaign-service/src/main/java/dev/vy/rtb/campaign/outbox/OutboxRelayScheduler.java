package dev.vy.rtb.campaign.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the relay on a fixed delay. A failure is logged and retried on the next tick: Kafka being
 * down must never take the API down. The topic is created lazily so the service starts without it.
 */
public final class OutboxRelayScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

  private final OutboxRelay relay;
  private final Runnable ensureTopic;
  private boolean topicReady;

  public OutboxRelayScheduler(OutboxRelay relay, Runnable ensureTopic) {
    this.relay = relay;
    this.ensureTopic = ensureTopic;
  }

  @Scheduled(fixedDelayString = "${rtb.outbox.relay.poll-ms:200}")
  void tick() {
    try {
      if (!topicReady) {
        ensureTopic.run();
        topicReady = true;
      }
      int sent = relay.runOnce();
      if (sent > 0) {
        log.debug("Relayed {} outbox rows", sent);
      }
    } catch (RuntimeException e) {
      log.warn("Outbox relay failed, will retry: {}", e.toString());
    }
  }
}
