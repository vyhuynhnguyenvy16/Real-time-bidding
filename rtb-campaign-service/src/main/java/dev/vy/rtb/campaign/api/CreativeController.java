package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.CampaignStatus;
import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.CreativeStatus;
import dev.vy.rtb.campaign.domain.InvalidStateException;
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
import org.springframework.transaction.support.TransactionOperations;
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
  private final TransactionOperations transactions;

  CreativeController(
      CampaignRepository campaigns,
      CreativeRepository creatives,
      CreativePatcher patcher,
      TransactionOperations transactions) {
    this.campaigns = campaigns;
    this.creatives = creatives;
    this.patcher = patcher;
    this.transactions = transactions;
  }

  @PostMapping
  ResponseEntity<CreativeResponse> create(
      @PathVariable UUID campaignId,
      @RequestBody CreativeRequest body,
      @AuthenticationPrincipal Jwt jwt) {
    UUID advertiser = CampaignController.advertiserOf(jwt);
    requireOpen(requireCampaign(advertiser, campaignId));
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
    requireOpen(requireCampaign(advertiser, campaignId));
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
    return transition(jwt, campaignId, id, false, Creative::pause);
  }

  @PostMapping("/{id}/resume")
  ResponseEntity<CreativeResponse> resume(
      @PathVariable UUID campaignId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return transition(jwt, campaignId, id, true, Creative::resume);
  }

  @PostMapping("/{id}/archive")
  ResponseEntity<CreativeResponse> archive(
      @PathVariable UUID campaignId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return transition(jwt, campaignId, id, false, Creative::archive);
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

  /** A creative cannot be added to, edited on or brought back on a finished campaign. */
  private static void requireOpen(Stored<Campaign> campaign) {
    CampaignStatus status = campaign.value().status();
    if (status == CampaignStatus.ARCHIVED || status == CampaignStatus.COMPLETED) {
      throw new InvalidStateException(
          status, "change the creatives of", "the campaign is finished");
    }
  }

  /**
   * The campaign row is locked for the whole transition, the same lock activate and resume take, so
   * "an ACTIVE campaign always has an active creative" cannot be broken by two requests crossing.
   * Taking the last active creative away from an ACTIVE campaign is refused (brief Q4). No If-Match
   * is sent, so when an unlocked edit lands in between, read again and retry.
   */
  private ResponseEntity<CreativeResponse> transition(
      Jwt jwt,
      UUID campaignId,
      UUID id,
      boolean needsOpenCampaign,
      Function<Creative, Creative> action) {
    UUID advertiser = CampaignController.advertiserOf(jwt);
    for (int attempt = 1; ; attempt++) {
      try {
        return transactions.execute(
            tx -> {
              Stored<Campaign> campaign =
                  campaigns
                      .findByIdForUpdate(advertiser, campaignId)
                      .orElseThrow(CampaignNotFoundException::new);
              Stored<Creative> stored = find(advertiser, campaignId, id);
              if (needsOpenCampaign) {
                requireOpen(campaign);
              }
              Creative changed = action.apply(stored.value());
              if (changed == stored.value()) {
                return ok(stored);
              }
              requireAnotherActive(advertiser, campaign.value(), stored.value(), changed);
              return ok(creatives.update(stored, changed));
            });
      } catch (VersionConflictException e) {
        if (attempt >= CampaignController.MAX_TRANSITION_ATTEMPTS) {
          throw e;
        }
      }
    }
  }

  private void requireAnotherActive(
      UUID advertiser, Campaign campaign, Creative before, Creative after) {
    boolean leavesActive =
        before.status() == CreativeStatus.ACTIVE && after.status() != CreativeStatus.ACTIVE;
    if (leavesActive
        && campaign.status() == CampaignStatus.ACTIVE
        && creatives.countActive(advertiser, campaign.id()) <= 1) {
      throw new LastActiveCreativeException();
    }
  }

  private static ResponseEntity<CreativeResponse> ok(Stored<Creative> stored) {
    return ResponseEntity.ok()
        .eTag(String.valueOf(stored.version()))
        .body(CreativeResponse.from(stored));
  }
}
