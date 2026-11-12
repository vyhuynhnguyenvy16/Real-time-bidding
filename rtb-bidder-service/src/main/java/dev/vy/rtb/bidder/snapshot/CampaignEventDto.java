package dev.vy.rtb.bidder.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * The consumer's own copy of the {@code campaign-updates} event v1 (see
 * docs/event-schema-campaign-updates.md). It keeps only the fields the bidder uses and ignores any
 * field added later, so a producer may add fields without breaking this service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CampaignEventDto(
    int schemaVersion, long revision, CampaignDto campaign, List<CreativeDto> creatives) {

  static final int SUPPORTED_SCHEMA = 1;

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CampaignDto(
      UUID id,
      String status,
      long maxBidCpmMicros,
      BidStrategyDto bidStrategy,
      String startAt,
      String endAt,
      TargetingDto targeting) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record BidStrategyDto(String type, Long cpmMicros) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record TargetingDto(
      List<String> countries,
      List<Integer> deviceTypes,
      List<String> categories,
      List<String> publisherBlocklist,
      String scheduleMask) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CreativeDto(UUID id, int width, int height, String markup, String landingUrl) {}
}
