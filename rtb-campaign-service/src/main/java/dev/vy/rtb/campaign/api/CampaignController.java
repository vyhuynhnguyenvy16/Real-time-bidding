package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.persistence.CampaignRepository;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Campaign endpoints of docs/api-contract.md 3.2. The tenant always comes from the token. */
@RestController
@RequestMapping("/api/v1/campaigns")
class CampaignController {

  private final CampaignRepository campaigns;

  CampaignController(CampaignRepository campaigns) {
    this.campaigns = campaigns;
  }

  @GetMapping("/{id}")
  ResponseEntity<CampaignResponse> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    var stored =
        campaigns.findById(advertiserOf(jwt), id).orElseThrow(CampaignNotFoundException::new);
    return ResponseEntity.ok()
        .eTag(String.valueOf(stored.version()))
        .body(CampaignResponse.from(stored));
  }

  /** The decoder already guarantees the claim is a UUID. */
  static UUID advertiserOf(Jwt jwt) {
    return UUID.fromString(jwt.getClaimAsString("advertiser_id"));
  }
}
