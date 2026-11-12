package dev.vy.rtb.bidder.api;

import dev.vy.rtb.bidder.kafka.CampaignUpdatesConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Probes for the load balancer. They move to the actuator on the admin port with the observability
 * PR (docs/api-contract.md section 4).
 */
@RestController
class HealthController {

  private final ObjectProvider<CampaignUpdatesConsumer> consumer;

  HealthController(ObjectProvider<CampaignUpdatesConsumer> consumer) {
    this.consumer = consumer;
  }

  @GetMapping("/livez")
  ResponseEntity<Void> live() {
    return ResponseEntity.ok().build();
  }

  /** UP only after the snapshot has caught up with the topic (KB-13). */
  @GetMapping("/readyz")
  ResponseEntity<Void> ready() {
    CampaignUpdatesConsumer updates = consumer.getIfAvailable();
    boolean ready = updates == null || updates.ready();
    return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).build();
  }
}
