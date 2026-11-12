package dev.vy.rtb.bidder.snapshot;

import java.util.UUID;

/** A creative as the bidder needs it: only ACTIVE creatives ever reach the bidder. */
public record BidderCreative(UUID id, int width, int height, String markup, String landingUrl) {}
