package dev.vy.rtb.bidder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Data plane: answers bid requests from an in-memory snapshot of the campaigns. */
@SpringBootApplication
public class BidderApplication {

  public static void main(String[] args) {
    SpringApplication.run(BidderApplication.class, args);
  }
}
