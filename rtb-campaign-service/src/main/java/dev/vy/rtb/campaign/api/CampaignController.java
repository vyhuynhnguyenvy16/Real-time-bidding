package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.CampaignStatus;
import dev.vy.rtb.campaign.persistence.CampaignCursor;
import dev.vy.rtb.campaign.persistence.CampaignPage;
import dev.vy.rtb.campaign.persistence.CampaignRepository;
import dev.vy.rtb.campaign.persistence.CreativeCounter;
import dev.vy.rtb.campaign.persistence.Stored;
import dev.vy.rtb.campaign.persistence.VersionConflictException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Campaign endpoints of docs/api-contract.md 3.2. The tenant always comes from the token. */
@RestController
@RequestMapping("/api/v1/campaigns")
class CampaignController {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;
  static final String MERGE_PATCH = "application/merge-patch+json";

  private final CampaignRepository campaigns;
  private final CreativeCounter creatives;
  private final CampaignPatcher patcher;
  private final Clock clock;

  CampaignController(
      CampaignRepository campaigns,
      CreativeCounter creatives,
      CampaignPatcher patcher,
      Clock clock) {
    this.campaigns = campaigns;
    this.creatives = creatives;
    this.patcher = patcher;
    this.clock = clock;
  }

  @PostMapping
  ResponseEntity<CampaignResponse> create(
      @RequestBody CampaignRequest body, @AuthenticationPrincipal Jwt jwt) {
    Campaign draft =
        Campaign.draft(
            UUID.randomUUID(), advertiserOf(jwt), CampaignRequestMapper.toNewCampaign(body));
    Stored<Campaign> stored = campaigns.insert(draft);
    return ResponseEntity.created(URI.create("/api/v1/campaigns/" + draft.id()))
        .eTag(String.valueOf(stored.version()))
        .body(CampaignResponse.from(stored));
  }

  @GetMapping("/{id}")
  ResponseEntity<CampaignResponse> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ok(find(advertiserOf(jwt), id));
  }

  @GetMapping
  CampaignListResponse list(
      @RequestParam(required = false) String limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String status,
      @AuthenticationPrincipal Jwt jwt) {
    CampaignPage page =
        campaigns.list(
            advertiserOf(jwt), parseStatus(status), parseCursor(cursor), parseLimit(limit));
    List<CampaignResponse> items = page.items().stream().map(CampaignResponse::from).toList();
    return new CampaignListResponse(
        items, page.next() == null ? null : CursorCodec.encode(page.next()));
  }

  @PatchMapping(path = "/{id}", consumes = MERGE_PATCH)
  ResponseEntity<CampaignResponse> patch(
      @PathVariable UUID id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody Map<String, Object> patch,
      @AuthenticationPrincipal Jwt jwt) {
    long expectedVersion = parseIfMatch(ifMatch);
    Stored<Campaign> stored = find(advertiserOf(jwt), id);
    if (expectedVersion != stored.version()) {
      throw new VersionConflictException(id, expectedVersion);
    }
    // A change that lands between the read above and the write makes update() throw the same.
    return ok(campaigns.update(stored, patcher.apply(stored.value(), patch)));
  }

  @PostMapping("/{id}/activate")
  ResponseEntity<CampaignResponse> activate(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    UUID advertiser = advertiserOf(jwt);
    return transition(
        advertiser, id, c -> c.activate(clock.instant(), creatives.countActive(advertiser, id)));
  }

  @PostMapping("/{id}/pause")
  ResponseEntity<CampaignResponse> pause(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return transition(advertiserOf(jwt), id, Campaign::pause);
  }

  @PostMapping("/{id}/resume")
  ResponseEntity<CampaignResponse> resume(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return transition(advertiserOf(jwt), id, Campaign::resume);
  }

  @PostMapping("/{id}/archive")
  ResponseEntity<CampaignResponse> archive(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return transition(advertiserOf(jwt), id, Campaign::archive);
  }

  // --- helpers --------------------------------------------------------------------------------

  private Stored<Campaign> find(UUID advertiser, UUID id) {
    return campaigns.findById(advertiser, id).orElseThrow(CampaignNotFoundException::new);
  }

  /** A transition that changes nothing (pause on a paused campaign) is not written. */
  private ResponseEntity<CampaignResponse> transition(
      UUID advertiser, UUID id, Function<Campaign, Campaign> action) {
    Stored<Campaign> stored = find(advertiser, id);
    Campaign changed = action.apply(stored.value());
    return ok(changed == stored.value() ? stored : campaigns.update(stored, changed));
  }

  private static ResponseEntity<CampaignResponse> ok(Stored<Campaign> stored) {
    return ResponseEntity.ok()
        .eTag(String.valueOf(stored.version()))
        .body(CampaignResponse.from(stored));
  }

  static long parseIfMatch(String header) {
    if (header == null || header.isBlank()) {
      throw new PreconditionRequiredException();
    }
    String text = header.trim();
    if (text.startsWith("W/")) {
      text = text.substring(2);
    }
    if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
      text = text.substring(1, text.length() - 1);
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw new BadRequestException("If-Match", "must be the ETag returned by the server");
    }
  }

  private static int parseLimit(String text) {
    if (text == null) {
      return DEFAULT_LIMIT;
    }
    try {
      int limit = Integer.parseInt(text);
      if (limit >= 1 && limit <= MAX_LIMIT) {
        return limit;
      }
    } catch (NumberFormatException ignored) {
      // reported below
    }
    throw new BadRequestException("limit", "must be a number from 1 to " + MAX_LIMIT);
  }

  private static CampaignStatus parseStatus(String text) {
    if (text == null) {
      return null;
    }
    try {
      return CampaignStatus.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("status", "must be one of " + List.of(CampaignStatus.values()));
    }
  }

  private static CampaignCursor parseCursor(String text) {
    return text == null ? null : CursorCodec.decode(text);
  }

  /** The decoder already guarantees the claim is a UUID. */
  static UUID advertiserOf(Jwt jwt) {
    return UUID.fromString(jwt.getClaimAsString("advertiser_id"));
  }
}
