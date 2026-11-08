package dev.vy.rtb.campaign.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.IF_MATCH;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.domain.PacingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Cross-cutting rules: request ids, 401 header, body size, no-op PATCH, strict numbers. */
class CampaignHardeningApiTest extends ApiTestBase {

  private static NewCampaign input() {
    return NewCampaign.builder()
        .name("Hardening")
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
  void aSafeRequestIdIsEchoedOnEveryResponseEvenA401() throws Exception {
    mockMvc
        .perform(get("/api/v1/campaigns").header("X-Request-Id", "trace-42.a_B"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Request-Id", "trace-42.a_B"))
        .andExpect(jsonPath("$.requestId").value("trace-42.a_B"));
  }

  @Test
  void anUnsafeRequestIdIsReplacedBecauseItCouldForgeLogLines() throws Exception {
    String forged = "x\"} {\"level\":\"ERROR\"";

    mockMvc
        .perform(get("/api/v1/campaigns").header("X-Request-Id", forged))
        .andExpect(status().isUnauthorized())
        .andExpect(
            result -> {
              String echoed = result.getResponse().getHeader("X-Request-Id");
              assertThat(echoed).isNotEqualTo(forged);
              assertThat(UUID.fromString(echoed)).isNotNull();
            });
  }

  @Test
  void a401AsksForABearerToken() throws Exception {
    mockMvc
        .perform(get("/api/v1/campaigns"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"));
  }

  @Test
  void aBodyOverTwoHundredFiftySixKilobytesIsRefused() throws Exception {
    UUID advertiser = givenAdvertiser();
    byte[] huge =
        ("{\"name\": \"" + "a".repeat(256 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);

    mockMvc
        .perform(
            post("/api/v1/campaigns")
                .header(AUTHORIZATION, bearer(advertiser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(huge))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
  }

  @Test
  void anEmptyPatchAndAPatchWithTheSameValuesWriteNothing() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    for (String body : new String[] {"{}", "{\"name\": \"Hardening\"}"}) {
      mockMvc
          .perform(
              patch("/api/v1/campaigns/" + id)
                  .header(AUTHORIZATION, bearer(advertiser))
                  .header(IF_MATCH, "\"1\"")
                  .contentType("application/merge-patch+json")
                  .content(body))
          .andExpect(status().isOk())
          .andExpect(header().string(ETAG, "\"1\""))
          .andExpect(jsonPath("$.version").value(1));
    }
    assertThat(repository.findById(advertiser, id).orElseThrow().version()).isEqualTo(1);
  }

  @Test
  void aMissingIfMatchOnSomeoneElsesCampaignIsStillA404() throws Exception {
    UUID mine = givenAdvertiser();
    UUID other = givenAdvertiser();
    UUID id = givenCampaign(other, input()).value().id();

    mockMvc
        .perform(
            patch("/api/v1/campaigns/" + id)
                .header(AUTHORIZATION, bearer(mine))
                .contentType("application/merge-patch+json")
                .content("{\"name\": \"x\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aFractionalAmountIsRejectedNotTruncated() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    mockMvc
        .perform(
            post("/api/v1/campaigns")
                .header(AUTHORIZATION, bearer(advertiser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CampaignJson.VALID.replace("100000000,", "100000000.9,")))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            patch("/api/v1/campaigns/" + id)
                .header(AUTHORIZATION, bearer(advertiser))
                .header(IF_MATCH, "\"1\"")
                .contentType("application/merge-patch+json")
                .content("{\"dailyBudgetMicros\": 1.5}"))
        .andExpect(status().isBadRequest());
    assertThat(repository.findById(advertiser, id).orElseThrow().version()).isEqualTo(1);
  }
}
