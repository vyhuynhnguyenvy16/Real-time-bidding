package dev.vy.rtb.bidder.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * The supported subset of an OpenRTB 2.6 BidRequest (docs/api-contract.md 1.1). Every other field
 * is ignored. Device IP and user agent are deliberately not read, so they can never be logged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OpenRtbRequest(
    String id,
    List<Imp> imp,
    Site site,
    App app,
    Device device,
    List<String> cur,
    Integer tmax,
    Integer test) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Imp(String id, Banner banner, BigDecimal bidfloor, String bidfloorcur) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Banner(Integer w, Integer h, List<Format> format) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Format(Integer w, Integer h) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Site(String domain, List<String> cat) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record App(String bundle, List<String> cat) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Device(Integer devicetype, Geo geo) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Geo(String country) {}
}
