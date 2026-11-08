package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.CampaignStatus;
import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.CreativeStatus;
import dev.vy.rtb.campaign.persistence.CampaignRepository;
import dev.vy.rtb.campaign.persistence.CreativeRepository;
import dev.vy.rtb.campaign.persistence.Stored;
import dev.vy.rtb.campaign.persistence.VersionConflictException;
import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Creative endpoints under a campaign. Every call first proves the campaign is the caller's, so
 * another tenant's campaign is a 404 whatever else was sent.
 */
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/creatives")
class CreativeController {

  private final CampaignRepository campaigns;
  private final CreativeRepository creatives;
  private final CreativePatcher patcher;

  CreativeController(
      CampaignRepository campaigns, CreativeRepository creatives, CreativePatcher patcher) {
    this.campaigns = campaigns;
    this.creatives = creatives;
    this.patcher = patcher;
  }

  @PostMapping
  ResponseEntity<CreativeResponse> create(
      @PathVariable UUID campaignId,
      @RequestBody CreativeRequest body,
      @AuthenticationPrincipal Jwt jwt) {
    UUID advertiser = CampaignController.advertiserOf(jwt);
    requireCampaign(advertiser, campaignId);
    Creative creative =
        Creative.create(UUID.randomUUID(), campaignId, advertiser, body.toNewCreative());
    Stored<Creative> stored = creatives.insert(creative);
    return ResponseEntity.created(
            URI.create("/api/v1/campaigns/" + campaignId + "/creatives/" + creative.id()))
        .eTag(String.valueOf(stored.version()))
        .body(CreativeResponse.from(stored));
  }

  @GetMapping
  Map<String, List<CreativeResponse>> list(
      @PathVariable UUID campaignId, @AuthenticationPrincipal Jwt jwt) {
    UUID advertiser = CampaignController.advertiserOf(jwt);
    requireCampaign(advertiser, campaignId);
    return Map.of(
        "items",
        creatives.listByCampaign(advertiser, campaignId).stream()
            .map(CreativeResponse::from)
            .toList());
  }

  @GetMapping("/{id}")
  ResponseEntity<CreativeResponse> get(
      @PathVariable UUID campaignId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    UUID advertiser = CampaignController.advertiserOf(jwt);
    requireCampaign(advertiser, campaignId);
    return ok(find(advertiser, campaignId, id));
  }

  @PatchMapping(path = "/{id}", consumes = CampaignController.MERGE_PATCH)
  ResponseEntity<CreativeResponse> patch(
      @PathVariable UUID campaignId,
      @PathVariable UUID id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody Map<String, Object> patch,
      @AuthenticationPrincipal Jwt jwt) {
    UUID advertiser = CampaignController.advertiserOf(jwt);
    requireCampaign(advertiser, campaignId);
    Stored<Creative> stored = find(advertiser, campaignId, id);
    long expected = CampaignController.parseIfMatch(ifMatch);
    if (expected != stored.version()) {
      throw new VersionConflictException(id, expected);
    }
    Creative revised = patcher.apply(stored.value(), patch);
    if (revised.equals(stored.value())) {
      return ok(stored); // nothing changed: no write, no new version
    }
    return ok(creatives.update(stored, revised));
  }

  @PostMapping("/{id}/pause")
  ResponseEntity<CreativeResponse> pause(
      @PathVariable UUID campaignId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return transition(jwt, campaignId, id, Creative::pause);
  }

  @PostMapping("/{id}/resume")
  ResponseEntity<CreativeResponse> resume(
      @PathVariable UUID campaignId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return transition(jwt, campaignId, id, Creative::resume);
  }

  @PostMapping("/{id}/archive")
  ResponseEntity<CreativeResponse> archive(
      @PathVariable UUID campaignId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return transition(jwt, campaignId, id, Creative::archive);
  }

  // --- helpers --------------------------------------------------------------------------------

  private Stored<Campaign> requireCampaign(UUID advertiser, UUID campaignId) {
    return campaigns.findById(advertiser, campaignId).orElseThrow(CampaignNotFoundException::new);
  }

  private Stored<Creative> find(UUID advertiser, UUID campaignId, UUID id) {
    return creatives
        .findById(advertiser, campaignId, id)
        .orElseThrow(CreativeNotFoundException::new);
  }

  /**
   * No If-Match is sent, so when someone else changes the creative in between, read again and
   * retry. Taking the last active creative away from an ACTIVE campaign is refused (brief Q4); two
   * requests racing for the last two creatives can still slip through, which the bidder tolerates.
   */
  private ResponseEntity<CreativeResponse> transition(
      Jwt jwt, UUID campaignId, UUID id, Function<Creative, Creative> action) {
    UUID advertiser = CampaignController.advertiserOf(jwt);
    for (int attempt = 1; ; attempt++) {
      Stored<Campaign> campaign = requireCampaign(advertiser, campaignId);
      Stored<Creative> stored = find(advertiser, campaignId, id);
      Creative changed = action.apply(stored.value());
      if (changed == stored.value()) {
        return ok(stored);
      }
      boolean leavesActive =
          stored.value().status() == CreativeStatus.ACTIVE
              && changed.status() != CreativeStatus.ACTIVE;
      if (leavesActive
          && campaign.value().status() == CampaignStatus.ACTIVE
          && creatives.countActive(advertiser, campaignId) <= 1) {
        throw new LastActiveCreativeException();
      }
      try {
        return ok(creatives.update(stored, changed));
      } catch (VersionConflictException e) {
        if (attempt >= CampaignController.MAX_TRANSITION_ATTEMPTS) {
          throw e;
        }
      }
    }
  }

  private static ResponseEntity<CreativeResponse> ok(Stored<Creative> stored) {
    return ResponseEntity.ok()
        .eTag(String.valueOf(stored.version()))
        .body(CreativeResponse.from(stored));
  }
}
