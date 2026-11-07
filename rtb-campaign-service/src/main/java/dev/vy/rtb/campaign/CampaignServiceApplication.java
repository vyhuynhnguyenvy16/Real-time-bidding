package dev.vy.rtb.campaign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Control plane: campaigns today; creatives, outbox and reporting come in later PRs. */
@SpringBootApplication
public class CampaignServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CampaignServiceApplication.class, args);
  }
}
