package dev.vy.rtb.bidder.api;

import dev.vy.rtb.bidder.kafka.CampaignUpdatesConsumer;
import dev.vy.rtb.bidder.match.BidRequest;
import dev.vy.rtb.bidder.match.Decision;
import dev.vy.rtb.bidder.match.Matcher;
import dev.vy.rtb.bidder.snapshot.SnapshotStore;
import dev.vy.rtb.common.NoBidReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /openrtb/2.6/bid}: the hot path. Reads only the in-memory snapshot. */
@RestController
class BidController {

  static final String SEAT = "rtb-service";
  static final int BID_TTL_SECONDS = 120;
  private static final long DEFAULT_DEADLINE_MS = 80;
  private static final long TMAX_MARGIN_MS = 20;

  private final SnapshotStore store;
  private final Clock clock;
  private final byte[] exchangeKey;
  private final ObjectProvider<CampaignUpdatesConsumer> consumer;

  BidController(
      SnapshotStore store,
      Clock clock,
      @Value("${rtb.bidder.exchange-key}") String exchangeKey,
      ObjectProvider<CampaignUpdatesConsumer> consumer) {
    this.store = store;
    this.clock = clock;
    this.exchangeKey = exchangeKey.getBytes(StandardCharsets.UTF_8);
    this.consumer = consumer;
  }

  @PostMapping("/openrtb/2.6/bid")
  ResponseEntity<BidResponse> bid(
      @RequestHeader(value = "X-Exchange-Key", required = false) String key,
      @RequestBody OpenRtbRequest body) {
    long started = System.nanoTime();
    if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), exchangeKey)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    CampaignUpdatesConsumer updates = consumer.getIfAvailable();
    if (updates != null && !updates.ready()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    BidRequest request = RequestMapper.toBidRequest(body);
    boolean debug = body.test() != null && body.test() == 1;

    Decision decision = Matcher.decide(store.campaigns(), request, clock.instant());

    long elapsedMs = (System.nanoTime() - started) / 1_000_000;
    if (elapsedMs > deadlineMs(body.tmax())) {
      return noBid(NoBidReason.DEADLINE_EXCEEDED, debug);
    }
    return switch (decision) {
      case Decision.NoBid n -> noBid(n.reason(), debug);
      case Decision.Bid b -> ResponseEntity.ok(response(body, request, b));
    };
  }

  /** {@code min(tmax - 20 ms, 80 ms)}; 80 ms when the exchange sent no tmax. */
  static long deadlineMs(Integer tmax) {
    if (tmax == null) {
      return DEFAULT_DEADLINE_MS;
    }
    return Math.min(tmax - TMAX_MARGIN_MS, DEFAULT_DEADLINE_MS);
  }

  private static ResponseEntity<BidResponse> noBid(NoBidReason reason, boolean debug) {
    ResponseEntity.HeadersBuilder<?> builder = ResponseEntity.noContent();
    if (debug) {
      builder.header("X-NoBid-Reason", reason.name());
    }
    return builder.build();
  }

  private static BidResponse response(OpenRtbRequest body, BidRequest request, Decision.Bid bid) {
    BidResponse.Bid out =
        new BidResponse.Bid(
            UUID.randomUUID().toString(),
            body.imp().get(0).id(),
            bid.priceCpmMicros() / 1_000_000.0,
            bid.markup(),
            bid.creativeId().toString(),
            bid.campaignId().toString(),
            request.width(),
            request.height(),
            BID_TTL_SECONDS);
    return new BidResponse(body.id(), "USD", List.of(new BidResponse.SeatBid(SEAT, List.of(out))));
  }
}
