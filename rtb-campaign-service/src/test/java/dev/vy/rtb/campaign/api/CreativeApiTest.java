package dev.vy.rtb.campaign.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.IF_MATCH;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.domain.NewCreative;
import dev.vy.rtb.campaign.domain.PacingMode;
import dev.vy.rtb.campaign.persistence.CreativeRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Seam: /api/v1/campaigns/{id}/creatives. Real PostgreSQL, real security chain. */
class CreativeApiTest extends ApiTestBase {

  private static final String VALID =
      """
      {"name": "Banner A", "width": 300, "height": 250,
       "markup": "<a href=\\"${CLICK_URL}\\"><img src=\\"https://cdn.example.com/a.png\\"></a>",
       "landingUrl": "https://example.com/landing"}
      """;

  @Autowired CreativeRepository creatives;

  private static NewCampaign campaignInput() {
    return NewCampaign.builder()
        .name("Creatives")
        .dailyBudgetMicros(100_000_000)
        .totalBudgetMicros(1_500_000_000)
        .maxBidCpmMicros(2_000_000)
        .bidStrategy(BidStrategy.fixedCpm(850_000))
        .pacingMode(PacingMode.EVEN)
        .startAt(Instant.now().minus(3, ChronoUnit.DAYS))
        .endAt(Instant.now().plus(30, ChronoUnit.DAYS))
        .build();
  }

  private UUID givenDraftCampaign(UUID advertiser) {
    return givenCampaign(advertiser, campaignInput()).value().id();
  }

  /** An ACTIVE campaign with one active creative, built straight in the database. */
  private UUID givenActiveCampaignWithCreatives(UUID advertiser, int creativeCount) {
    UUID id = UUID.randomUUID();
    repository.insert(Campaign.draft(id, advertiser, campaignInput()).activate(Instant.now(), 1));
    for (int i = 0; i < creativeCount; i++) {
      givenCreative(advertiser, id);
    }
    return id;
  }

  private UUID givenCreative(UUID advertiser, UUID campaign) {
    NewCreative input =
        NewCreative.builder()
            .name("Banner")
            .width(300)
            .height(250)
            .markup("<img src=\"https://cdn.example.com/a.png\">")
            .landingUrl("https://example.com")
            .build();
    Creative creative = Creative.create(UUID.randomUUID(), campaign, advertiser, input);
    return creatives.insert(creative).value().id();
  }

  private static String path(UUID campaign) {
    return "/api/v1/campaigns/" + campaign + "/creatives";
  }

  private ResultActions create(UUID advertiser, UUID campaign, String body) throws Exception {
    return mockMvc.perform(
        post(path(campaign))
            .header(AUTHORIZATION, bearer(advertiser))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions action(UUID advertiser, UUID campaign, UUID creative, String action)
      throws Exception {
    return mockMvc.perform(
        post(path(campaign) + "/" + creative + "/" + action)
            .header(AUTHORIZATION, bearer(advertiser)));
  }

  private ResultActions patchCreative(
      UUID advertiser, UUID campaign, UUID creative, String ifMatch, String body) throws Exception {
    var request =
        patch(path(campaign) + "/" + creative)
            .header(AUTHORIZATION, bearer(advertiser))
            .contentType("application/merge-patch+json")
            .content(body);
    if (ifMatch != null) {
      request.header(IF_MATCH, ifMatch);
    }
    return mockMvc.perform(request);
  }

  // --- create ---------------------------------------------------------------------------------

  @Test
  void createsAnActiveCreativeAtVersionOne() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);

    create(advertiser, campaign, VALID)
        .andExpect(status().isCreated())
        .andExpect(header().string(ETAG, "\"1\""))
        .andExpect(
            header().string(LOCATION, org.hamcrest.Matchers.startsWith(path(campaign) + "/")))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.campaignId").value(campaign.toString()))
        .andExpect(jsonPath("$.width").value(300))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void anInvalidCreativeIs400WithEveryProblemListed() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);

    create(
            advertiser,
            campaign,
            "{\"name\": \"\", \"width\": 1, \"height\": 1, \"markup\": \"<script>x</script>\","
                + " \"landingUrl\": \"http://x.com\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(
            jsonPath("$.errors[*].field")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "name", "size", "markup", "landingUrl")));
  }

  @Test
  void aMissingSizeIsAValidationErrorNotACrash() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);

    create(advertiser, campaign, "{\"name\": \"x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void aTwentyFirstCreativeIs409ButArchivingFreesASlot() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID first = null;
    for (int i = 0; i < 20; i++) {
      UUID id = givenCreative(advertiser, campaign);
      first = first == null ? id : first;
    }

    create(advertiser, campaign, VALID)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CREATIVE_LIMIT"));
    action(advertiser, campaign, first, "archive").andExpect(status().isOk());
    create(advertiser, campaign, VALID).andExpect(status().isCreated());
  }

  // --- tenant and auth ------------------------------------------------------------------------

  @Test
  void everyEndpointIs404ForAnotherAdvertisersCampaign() throws Exception {
    UUID owner = givenAdvertiser();
    UUID intruder = givenAdvertiser();
    UUID campaign = givenDraftCampaign(owner);
    UUID creative = givenCreative(owner, campaign);

    create(intruder, campaign, VALID).andExpect(status().isNotFound());
    mockMvc
        .perform(get(path(campaign)).header(AUTHORIZATION, bearer(intruder)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get(path(campaign) + "/" + creative).header(AUTHORIZATION, bearer(intruder)))
        .andExpect(status().isNotFound());
    patchCreative(intruder, campaign, creative, null, "{\"name\": \"x\"}")
        .andExpect(status().isNotFound());
    action(intruder, campaign, creative, "pause").andExpect(status().isNotFound());
  }

  @Test
  void aCreativeIdFromAnotherCampaignIs404() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID other = givenDraftCampaign(advertiser);
    UUID creative = givenCreative(advertiser, other);

    mockMvc
        .perform(get(path(campaign) + "/" + creative).header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isNotFound());
  }

  @Test
  void withoutATokenIs401() throws Exception {
    mockMvc.perform(get(path(UUID.randomUUID()))).andExpect(status().isUnauthorized());
  }

  // --- read -----------------------------------------------------------------------------------

  @Test
  void getsOneAndListsTheCampaignsCreativesOldestFirst() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID first = givenCreative(advertiser, campaign);
    UUID second = givenCreative(advertiser, campaign);
    givenCreative(advertiser, givenDraftCampaign(advertiser));

    mockMvc
        .perform(get(path(campaign) + "/" + first).header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isOk())
        .andExpect(header().string(ETAG, "\"1\""))
        .andExpect(jsonPath("$.id").value(first.toString()));
    mockMvc
        .perform(get(path(campaign)).header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(first.toString()))
        .andExpect(jsonPath("$.items[1].id").value(second.toString()));
  }

  // --- patch ----------------------------------------------------------------------------------

  @Test
  void patchChangesOnlyTheGivenFieldsAndBumpsTheVersion() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID creative = givenCreative(advertiser, campaign);

    patchCreative(advertiser, campaign, creative, "\"1\"", "{\"name\": \"Renamed\"}")
        .andExpect(status().isOk())
        .andExpect(header().string(ETAG, "\"2\""))
        .andExpect(jsonPath("$.name").value("Renamed"))
        .andExpect(jsonPath("$.width").value(300))
        .andExpect(jsonPath("$.version").value(2));
  }

  @Test
  void patchRules() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID creative = givenCreative(advertiser, campaign);

    patchCreative(advertiser, campaign, creative, null, "{\"name\": \"x\"}")
        .andExpect(status().isPreconditionRequired());
    patchCreative(advertiser, campaign, creative, "\"9\"", "{\"name\": \"x\"}")
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    patchCreative(advertiser, campaign, creative, "\"1\"", "{\"status\": \"ARCHIVED\"}")
        .andExpect(status().isBadRequest());
    patchCreative(advertiser, campaign, creative, "\"1\"", "{\"markup\": \"<script>x</script>\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("markup"));
    patchCreative(advertiser, campaign, creative, "\"1\"", "{\"width\": 728, \"height\": 90}")
        .andExpect(status().isOk());
    // Nothing above that failed wrote anything: only the last one bumped the version.
    assertThat(creatives.findById(advertiser, campaign, creative).orElseThrow().version())
        .isEqualTo(2);
  }

  @Test
  void anEmptyPatchWritesNothing() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID creative = givenCreative(advertiser, campaign);

    patchCreative(advertiser, campaign, creative, "\"1\"", "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void anArchivedCreativeCannotBeEdited() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID creative = givenCreative(advertiser, campaign);
    action(advertiser, campaign, creative, "archive").andExpect(status().isOk());

    patchCreative(advertiser, campaign, creative, "\"2\"", "{\"name\": \"x\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  // --- lifecycle ------------------------------------------------------------------------------

  @Test
  void pauseResumeArchive() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID creative = givenCreative(advertiser, campaign);

    action(advertiser, campaign, creative, "pause")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"));
    action(advertiser, campaign, creative, "pause")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2)); // repeating is a no-op
    action(advertiser, campaign, creative, "resume")
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    action(advertiser, campaign, creative, "archive")
        .andExpect(jsonPath("$.status").value("ARCHIVED"));
    action(advertiser, campaign, creative, "resume")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  // --- the rule between campaign and creatives (brief Q4) --------------------------------------

  @Test
  void theLastActiveCreativeOfAnActiveCampaignCannotBePausedOrArchived() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenActiveCampaignWithCreatives(advertiser, 1);
    UUID only = creatives.listByCampaign(advertiser, campaign).get(0).value().id();

    action(advertiser, campaign, only, "pause")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LAST_ACTIVE_CREATIVE"));
    action(advertiser, campaign, only, "archive")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LAST_ACTIVE_CREATIVE"));
  }

  @Test
  void anExtraCreativeOrADraftCampaignLiftsTheRule() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID active = givenActiveCampaignWithCreatives(advertiser, 2);
    UUID draft = givenDraftCampaign(advertiser);
    UUID inDraft = givenCreative(advertiser, draft);
    UUID one = creatives.listByCampaign(advertiser, active).get(0).value().id();

    action(advertiser, active, one, "pause").andExpect(status().isOk());
    action(advertiser, draft, inDraft, "archive").andExpect(status().isOk());
  }

  @Test
  void aCampaignNeedsACreativeToActivate() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    String activate = "/api/v1/campaigns/" + campaign + "/activate";

    mockMvc
        .perform(post(activate).header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    givenCreative(advertiser, campaign);
    mockMvc
        .perform(post(activate).header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  // --- review findings: the invariant "an ACTIVE campaign has an active creative" ---------------

  private ResultActions campaignAction(UUID advertiser, UUID campaign, String action)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/campaigns/" + campaign + "/" + action)
            .header(AUTHORIZATION, bearer(advertiser)));
  }

  @Test
  void aPausedCampaignCannotBeResumedWithoutAnActiveCreative() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenActiveCampaignWithCreatives(advertiser, 1);
    UUID only = creatives.listByCampaign(advertiser, campaign).get(0).value().id();

    campaignAction(advertiser, campaign, "pause").andExpect(status().isOk());
    // The campaign is paused, so the last creative may go ...
    action(advertiser, campaign, only, "pause").andExpect(status().isOk());
    // ... but the campaign cannot come back without one.
    campaignAction(advertiser, campaign, "resume")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    action(advertiser, campaign, only, "resume").andExpect(status().isOk());
    campaignAction(advertiser, campaign, "resume")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void onlyPausedCreativesDoNotLetACampaignActivate() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID creative = givenCreative(advertiser, campaign);
    action(advertiser, campaign, creative, "pause").andExpect(status().isOk());

    campaignAction(advertiser, campaign, "activate").andExpect(status().isConflict());
  }

  @Test
  void aFinishedCampaignTakesNoNewOrEditedOrRevivedCreatives() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);
    UUID creative = givenCreative(advertiser, campaign);
    action(advertiser, campaign, creative, "pause").andExpect(status().isOk());
    campaignAction(advertiser, campaign, "archive").andExpect(status().isOk());

    create(advertiser, campaign, VALID)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    patchCreative(advertiser, campaign, creative, "\"2\"", "{\"name\": \"x\"}")
        .andExpect(status().isConflict());
    action(advertiser, campaign, creative, "resume").andExpect(status().isConflict());
    // Cleaning up is still allowed.
    action(advertiser, campaign, creative, "archive").andExpect(status().isOk());
  }

  @Test
  void landingUrlsWithCredentialsAndNulCharactersAre400NotAnError() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID campaign = givenDraftCampaign(advertiser);

    create(
            advertiser,
            campaign,
            VALID.replace("https://example.com/landing", "https://google.com@evil.example/"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("landingUrl"));
    create(advertiser, campaign, VALID.replace("Banner A", "a\\u0000b"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }
}
