package dev.vy.rtb.campaign.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.domain.PacingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Seam: GET /api/v1/campaigns with limit, cursor and status. */
class CampaignListApiTest extends ApiTestBase {

  private static NewCampaign input(String name) {
    return NewCampaign.builder()
        .name(name)
        .dailyBudgetMicros(100_000_000)
        .totalBudgetMicros(1_500_000_000)
        .maxBidCpmMicros(2_000_000)
        .bidStrategy(BidStrategy.fixedCpm(850_000))
        .pacingMode(PacingMode.EVEN)
        .startAt(Instant.parse("2026-11-10T00:00:00Z"))
        .endAt(Instant.parse("2026-12-10T00:00:00Z"))
        .build();
  }

  @Test
  void pagesThroughAllCampaignsOfTheCallerWithoutRepeatsOrGaps() throws Exception {
    UUID advertiser = givenAdvertiser();
    Set<String> created = new HashSet<>();
    for (int i = 0; i < 5; i++) {
      created.add(givenCampaign(advertiser, input("c" + i)).value().id().toString());
    }

    Set<String> seen = new HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      String url = "/api/v1/campaigns?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
      String body =
          mockMvc
              .perform(get(url).header(AUTHORIZATION, bearer(advertiser)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      java.util.List<String> ids = JsonPath.read(body, "$.items[*].id");
      assertThat(ids.size()).isLessThanOrEqualTo(2);
      seen.addAll(ids);
      cursor = JsonPath.read(body, "$.nextCursor");
      pages++;
    } while (cursor != null && pages < 10);

    assertThat(seen).isEqualTo(created);
    assertThat(pages).isEqualTo(3); // 2 + 2 + 1
  }

  @Test
  void theLastPageHasNoNextCursor() throws Exception {
    UUID advertiser = givenAdvertiser();
    givenCampaign(advertiser, input("only"));

    mockMvc
        .perform(get("/api/v1/campaigns").header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void anEmptyListIsStillAnObject() throws Exception {
    UUID advertiser = givenAdvertiser();

    mockMvc
        .perform(get("/api/v1/campaigns").header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void neverShowsCampaignsOfAnotherAdvertiser() throws Exception {
    UUID mine = givenAdvertiser();
    UUID other = givenAdvertiser();
    givenCampaign(mine, input("mine"));
    givenCampaign(other, input("theirs"));

    mockMvc
        .perform(get("/api/v1/campaigns").header(AUTHORIZATION, bearer(mine)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("mine"));
  }

  @Test
  void filtersByStatus() throws Exception {
    UUID advertiser = givenAdvertiser();
    var archived = givenCampaign(advertiser, input("old"));
    repository.update(archived, archived.value().archive());
    givenCampaign(advertiser, input("draft"));

    mockMvc
        .perform(get("/api/v1/campaigns?status=ARCHIVED").header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("old"));
  }

  @ParameterizedTest(name = "limit={0} is rejected")
  @ValueSource(strings = {"0", "-1", "201", "abc"})
  void rejectsALimitOutsideOneToTwoHundred(String limit) throws Exception {
    UUID advertiser = givenAdvertiser();

    mockMvc
        .perform(get("/api/v1/campaigns?limit=" + limit).header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("limit"));
  }

  @Test
  void rejectsAnUnknownStatusAndAGarbageCursor() throws Exception {
    UUID advertiser = givenAdvertiser();

    mockMvc
        .perform(get("/api/v1/campaigns?status=WEIRD").header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("status"));
    mockMvc
        .perform(
            get("/api/v1/campaigns?cursor=%25%25%25").header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("cursor"));
  }
}
