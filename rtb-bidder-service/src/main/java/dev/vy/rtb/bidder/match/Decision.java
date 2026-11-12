package dev.vy.rtb.bidder.match;

import dev.vy.rtb.common.NoBidReason;
import java.util.UUID;

/** The outcome of one bid request. A no-bid is a normal outcome, not an error. */
public sealed interface Decision {

  record Bid(
      UUID campaignId, UUID creativeId, long priceCpmMicros, String markup, String landingUrl)
      implements Decision {}

  record NoBid(NoBidReason reason) implements Decision {}
}
