package dev.vy.rtb.campaign.api;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.domain.PacingMode;
import dev.vy.rtb.campaign.domain.TargetingInput;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Seam: GET /api/v1/campaigns/{id} and the security and error rules every endpoint shares. */
class CampaignGetApiTest extends ApiTestBase {

  private static NewCampaign sampleInput() {
    return NewCampaign.builder()
        .name("Thể thao tháng 11")
        .dailyBudgetMicros(100_000_000)
        .totalBudgetMicros(1_500_000_000)
        .maxBidCpmMicros(2_000_000)
        .bidStrategy(BidStrategy.fixedCpm(850_000))
        .pacingMode(PacingMode.EVEN)
        .freqCapCount(3)
        .startAt(Instant.parse("2026-11-10T00:00:00Z"))
        .endAt(Instant.parse("2026-12-10T00:00:00Z"))
        .targeting(
            new TargetingInput(
                List.of("VNM"),
                List.of(1, 4, 5),
                List.of("iab17"),
                List.of("spam.example"),
                Map.of("sat", List.of(9, 10))))
        .build();
  }

  @Test
  void returnsTheCampaignOfTheCallersAdvertiserWithItsVersionAsEtag() throws Exception {
    UUID advertiser = givenAdvertiser();
    var stored = givenCampaign(advertiser, sampleInput());

    mockMvc
        .perform(
            get("/api/v1/campaigns/" + stored.value().id())
                .header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isOk())
        .andExpect(header().string(ETAG, "\"1\""))
        .andExpect(jsonPath("$.id").value(stored.value().id().toString()))
        .andExpect(jsonPath("$.advertiserId").value(advertiser.toString()))
        .andExpect(jsonPath("$.name").value("Thể thao tháng 11"))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.dailyBudgetMicros").value(100_000_000))
        .andExpect(jsonPath("$.totalBudgetMicros").value(1_500_000_000L))
        .andExpect(jsonPath("$.maxBidCpmMicros").value(2_000_000))
        .andExpect(jsonPath("$.bidStrategy.type").value("FIXED_CPM"))
        .andExpect(jsonPath("$.bidStrategy.cpmMicros").value(850_000))
        .andExpect(jsonPath("$.bidStrategy.cpcMicros").doesNotExist())
        .andExpect(jsonPath("$.pacingMode").value("EVEN"))
        .andExpect(jsonPath("$.freqCapCount").value(3))
        .andExpect(jsonPath("$.startAt").value("2026-11-10T00:00:00Z"))
        .andExpect(jsonPath("$.endAt").value("2026-12-10T00:00:00Z"))
        .andExpect(jsonPath("$.targeting.countries[0]").value("VNM"))
        .andExpect(jsonPath("$.targeting.deviceTypes.length()").value(3))
        .andExpect(jsonPath("$.targeting.categories[0]").value("iab17"))
        .andExpect(jsonPath("$.targeting.publisherBlocklist[0]").value("spam.example"))
        .andExpect(jsonPath("$.targeting.schedule.timezone").value("Asia/Ho_Chi_Minh"))
        .andExpect(jsonPath("$.targeting.schedule.hours.sat[0]").value(9))
        .andExpect(jsonPath("$.targeting.schedule.hours.sat[1]").value(10))
        .andExpect(jsonPath("$.targeting.schedule.hours.sun").doesNotExist())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.updatedAt").exists());
  }

  @Test
  void aCampaignThatRunsAtAllTimesHasAnEmptyScheduleHoursMap() throws Exception {
    UUID advertiser = givenAdvertiser();
    var input =
        NewCampaign.builder()
            .name("Always on")
            .dailyBudgetMicros(1_000_000)
            .totalBudgetMicros(1_000_000)
            .maxBidCpmMicros(1_000_000)
            .bidStrategy(BidStrategy.fixedCpm(500_000))
            .pacingMode(PacingMode.ASAP)
            .startAt(Instant.parse("2026-11-10T00:00:00Z"))
            .endAt(Instant.parse("2026-11-10T00:00:00Z").plus(1, ChronoUnit.DAYS))
            .build();
    var stored = givenCampaign(advertiser, input);

    mockMvc
        .perform(
            get("/api/v1/campaigns/" + stored.value().id())
                .header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targeting.schedule.hours").isEmpty())
        .andExpect(jsonPath("$.freqCapCount").doesNotExist());
  }

  @Test
  void answersAnUnknownCampaignWithANotFoundProblem() throws Exception {
    UUID advertiser = givenAdvertiser();

    mockMvc
        .perform(
            get("/api/v1/campaigns/" + UUID.randomUUID()).header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.requestId").exists());
  }

  @Test
  void aCampaignOfAnotherAdvertiserLooksExactlyLikeAnUnknownOne() throws Exception {
    UUID owner = givenAdvertiser();
    UUID intruder = givenAdvertiser();
    var stored = givenCampaign(owner, sampleInput());

    mockMvc
        .perform(
            get("/api/v1/campaigns/" + stored.value().id()).header(AUTHORIZATION, bearer(intruder)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.detail").value("Campaign not found"));
  }

  @Test
  void anIdThatIsNotAUuidIsNotFoundToo() throws Exception {
    UUID advertiser = givenAdvertiser();

    mockMvc
        .perform(get("/api/v1/campaigns/not-a-uuid").header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void rejectsARequestWithoutAToken() throws Exception {
    mockMvc
        .perform(get("/api/v1/campaigns/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void rejectsATokenSignedWithAnotherSecret() throws Exception {
    String forged =
        TestTokens.sign(
            "another-secret-another-secret-123456",
            UUID.randomUUID().toString(),
            Instant.now().plusSeconds(600));

    mockMvc
        .perform(
            get("/api/v1/campaigns/" + UUID.randomUUID()).header(AUTHORIZATION, "Bearer " + forged))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void rejectsAnExpiredToken() throws Exception {
    String expired =
        TestTokens.sign(
            TestTokens.SECRET, UUID.randomUUID().toString(), Instant.now().minusSeconds(3600));

    mockMvc
        .perform(
            get("/api/v1/campaigns/" + UUID.randomUUID())
                .header(AUTHORIZATION, "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsATokenWithoutATenant() throws Exception {
    String noTenant = TestTokens.sign(TestTokens.SECRET, null, Instant.now().plusSeconds(600));
    String badTenant =
        TestTokens.sign(TestTokens.SECRET, "not-a-uuid", Instant.now().plusSeconds(600));

    mockMvc
        .perform(
            get("/api/v1/campaigns/" + UUID.randomUUID())
                .header(AUTHORIZATION, "Bearer " + noTenant))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get("/api/v1/campaigns/" + UUID.randomUUID())
                .header(AUTHORIZATION, "Bearer " + badTenant))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void echoesTheCallersRequestIdAndCreatesOneWhenMissing() throws Exception {
    UUID advertiser = givenAdvertiser();

    mockMvc
        .perform(
            get("/api/v1/campaigns/" + UUID.randomUUID())
                .header(AUTHORIZATION, bearer(advertiser))
                .header("X-Request-Id", "req-123"))
        .andExpect(header().string("X-Request-Id", "req-123"))
        .andExpect(jsonPath("$.requestId").value("req-123"));

    mockMvc
        .perform(
            get("/api/v1/campaigns/" + UUID.randomUUID()).header(AUTHORIZATION, bearer(advertiser)))
        .andExpect(header().exists("X-Request-Id"));
  }
}
