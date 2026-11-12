package dev.vy.rtb.bidder.api;

import dev.vy.rtb.bidder.match.BidRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Checks an OpenRTB request and turns it into the matcher's input. */
final class RequestMapper {

  private static final BigDecimal MICROS = BigDecimal.valueOf(1_000_000);
  private static final BigDecimal MAX_FLOOR_USD = BigDecimal.valueOf(1_000);

  private RequestMapper() {}

  static BidRequest toBidRequest(OpenRtbRequest r) {
    if (r.id() == null || r.id().isBlank()) {
      throw new InvalidRequestException("id is required");
    }
    if (r.imp() == null || r.imp().isEmpty() || r.imp().get(0) == null) {
      throw new InvalidRequestException("imp must have at least one element");
    }
    if (r.cur() != null && !r.cur().isEmpty() && !r.cur().contains("USD")) {
      throw new InvalidRequestException("cur must contain USD");
    }
    OpenRtbRequest.Imp imp = r.imp().get(0);
    if (imp.id() == null || imp.id().isBlank()) {
      throw new InvalidRequestException("imp[0].id is required");
    }
    int[] size = sizeOf(imp.banner());
    String publisher = publisherOf(r);
    List<String> cat = r.site() != null ? r.site().cat() : r.app() != null ? r.app().cat() : null;
    Set<String> categories =
        cat == null
            ? Set.of()
            : cat.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    String country =
        r.device() != null && r.device().geo() != null ? r.device().geo().country() : null;
    Integer deviceType = r.device() != null ? r.device().devicetype() : null;
    return BidRequest.builder()
        .id(r.id())
        .size(size[0], size[1])
        .floorCpmMicros(floorMicros(imp))
        .country(country == null ? null : country.toUpperCase(Locale.ROOT))
        .deviceType(deviceType)
        .categories(categories)
        .publisherId(publisher)
        .build();
  }

  private static int[] sizeOf(OpenRtbRequest.Banner banner) {
    if (banner == null) {
      throw new InvalidRequestException("imp[0].banner is required");
    }
    Integer w = banner.w();
    Integer h = banner.h();
    if ((w == null || h == null) && banner.format() != null && !banner.format().isEmpty()) {
      OpenRtbRequest.Format first = banner.format().get(0);
      if (first != null) {
        w = first.w();
        h = first.h();
      }
    }
    if (w == null || h == null || w <= 0 || h <= 0) {
      throw new InvalidRequestException("imp[0].banner needs a positive w and h");
    }
    return new int[] {w, h};
  }

  /** The blocklist is lowercase, so the publisher is lowercased the same way. */
  private static String publisherOf(OpenRtbRequest r) {
    String id = null;
    if (r.site() != null && r.site().domain() != null && !r.site().domain().isBlank()) {
      id = r.site().domain();
    } else if (r.app() != null && r.app().bundle() != null && !r.app().bundle().isBlank()) {
      id = r.app().bundle();
    }
    if (id == null) {
      throw new InvalidRequestException("site.domain or app.bundle is required");
    }
    return id.toLowerCase(Locale.ROOT);
  }

  private static long floorMicros(OpenRtbRequest.Imp imp) {
    if (imp.bidfloorcur() != null && !"USD".equals(imp.bidfloorcur())) {
      throw new InvalidRequestException("bidfloorcur must be USD");
    }
    BigDecimal floor = imp.bidfloor();
    if (floor == null) {
      return 0;
    }
    if (floor.signum() < 0 || floor.compareTo(MAX_FLOOR_USD) > 0) {
      throw new InvalidRequestException("bidfloor must be between 0 and " + MAX_FLOOR_USD);
    }
    // Round up: a floor of 0.0000001 must not let a cheaper bid through.
    return floor.multiply(MICROS).setScale(0, RoundingMode.CEILING).longValueExact();
  }
}
