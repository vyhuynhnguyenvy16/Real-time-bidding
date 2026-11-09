package dev.vy.rtb.campaign.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.domain.PacingMode;
import dev.vy.rtb.campaign.persistence.CreativeRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/** Seam: POST /api/v1/campaigns/{id}/activate | pause | resume | archive. */
class CampaignActionsApiTest extends ApiTestBase {

  @MockitoBean CreativeRepository creatives;

  private static NewCampaign input(Instant endAt) {
    return NewCampaign.builder()
        .name("Actions")
        .dailyBudgetMicros(100_000_000)
        .totalBudgetMicros(1_500_000_000)
        .maxBidCpmMicros(2_000_000)
        .bidStrategy(BidStrategy.fixedCpm(850_000))
        .pacingMode(PacingMode.EVEN)
        .startAt(Instant.now().minus(3, ChronoUnit.DAYS))
        .endAt(endAt)
        .build();
  }

  private UUID givenDraft(UUID advertiser) {
    return givenCampaign(advertiser, input(Instant.now().plus(30, ChronoUnit.DAYS))).value().id();
  }

  private ResultActions act(UUID advertiser, UUID id, String action) throws Exception {
    return mockMvc.perform(
        post("/api/v1/campaigns/" + id + "/" + action).header(AUTHORIZATION, bearer(advertiser)));
  }

  @Test
  void activatesADraftThatHasACreative() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenDraft(advertiser);
    when(creatives.countActive(any(), any())).thenReturn(1);

    act(advertiser, id, "activate")
        .andExpect(status().isOk())
        .andExpect(header().string(ETAG, "\"2\""))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void refusesToActivateWithoutACreative() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenDraft(advertiser);
    when(creatives.countActive(any(), any())).thenReturn(0);

    act(advertiser, id, "activate")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(repository.findById(advertiser, id).orElseThrow().value().status().name())
        .isEqualTo("DRAFT");
  }

  @Test
  void refusesToActivateACampaignWhoseEndDateHasPassed() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id =
        givenCampaign(advertiser, input(Instant.now().minus(1, ChronoUnit.DAYS))).value().id();
    when(creatives.countActive(any(), any())).thenReturn(1);

    act(advertiser, id, "activate")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void pausesAndResumesAnActiveCampaign() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenDraft(advertiser);
    when(creatives.countActive(any(), any())).thenReturn(1);
    act(advertiser, id, "activate").andExpect(status().isOk());

    act(advertiser, id, "pause")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"));
    act(advertiser, id, "resume")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void pauseAndResumeAreIdempotentAndDoNotBumpTheVersion() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenDraft(advertiser);
    when(creatives.countActive(any(), any())).thenReturn(1);
    act(advertiser, id, "activate").andExpect(status().isOk());
    act(advertiser, id, "pause").andExpect(status().isOk()); // version 3

    act(advertiser, id, "pause")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"))
        .andExpect(jsonPath("$.version").value(3));
  }

  @Test
  void archivesAPausedCampaignButNotAnActiveOne() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenDraft(advertiser);
    when(creatives.countActive(any(), any())).thenReturn(1);
    act(advertiser, id, "activate").andExpect(status().isOk());

    act(advertiser, id, "archive")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    act(advertiser, id, "pause").andExpect(status().isOk());
    act(advertiser, id, "archive")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ARCHIVED"));
  }

  @Test
  void aDraftCannotBePaused() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenDraft(advertiser);

    act(advertiser, id, "pause")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void anotherAdvertisersCampaignLooksLikeItDoesNotExist() throws Exception {
    UUID mine = givenAdvertiser();
    UUID other = givenAdvertiser();
    UUID id = givenDraft(other);

    act(mine, id, "activate").andExpect(status().isNotFound());
    act(mine, id, "pause").andExpect(status().isNotFound());
    act(mine, id, "resume").andExpect(status().isNotFound());
    act(mine, id, "archive").andExpect(status().isNotFound());
  }

  @Test
  void requiresAToken() throws Exception {
    mockMvc
        .perform(post("/api/v1/campaigns/" + UUID.randomUUID() + "/activate"))
        .andExpect(status().isUnauthorized());
  }
}
