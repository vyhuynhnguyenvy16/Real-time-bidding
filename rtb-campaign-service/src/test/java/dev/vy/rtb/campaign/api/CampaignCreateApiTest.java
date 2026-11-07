package dev.vy.rtb.campaign.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vy.rtb.campaign.domain.CampaignStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Seam: POST /api/v1/campaigns. */
class CampaignCreateApiTest extends ApiTestBase {

  private MvcResult create(UUID advertiser, String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/campaigns")
                .header(AUTHORIZATION, bearer(advertiser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  @Test
  void createsADraftCampaignForTheCallersAdvertiser() throws Exception {
    UUID advertiser = givenAdvertiser();

    mockMvc
        .perform(
            post("/api/v1/campaigns")
                .header(AUTHORIZATION, bearer(advertiser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CampaignJson.VALID))
        .andExpect(status().isCreated())
        .andExpect(header().exists(LOCATION))
        .andExpect(header().string(ETAG, "\"1\""))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.advertiserId").value(advertiser.toString()))
        .andExpect(jsonPath("$.name").value("Thể thao tháng 11"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.targeting.categories[0]").value("iab17"))
        .andExpect(jsonPath("$.targeting.publisherBlocklist[0]").value("spam.example"))
        .andExpect(jsonPath("$.targeting.schedule.hours.mon.length()").value(5))
        .andExpect(jsonPath("$.targeting.schedule.hours.sun.length()").value(5));
  }

  @Test
  void theLocationPointsAtTheStoredCampaign() throws Exception {
    UUID advertiser = givenAdvertiser();

    MvcResult result = create(advertiser, CampaignJson.VALID);

    String location = result.getResponse().getHeader(LOCATION);
    UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    assertThat(location).isEqualTo("/api/v1/campaigns/" + id);
    var stored = repository.findById(advertiser, id).orElseThrow();
    assertThat(stored.value().status()).isEqualTo(CampaignStatus.DRAFT);
    assertThat(stored.version()).isEqualTo(1);
  }

  @Test
  void theServerOwnsIdStatusAndAdvertiser() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID forgedId = UUID.randomUUID();
    String body =
        CampaignJson.VALID.replaceFirst(
            "\\{",
            "{ \"id\": \""
                + forgedId
                + "\", \"status\": \"ACTIVE\", \"version\": 99,"
                + " \"advertiserId\": \""
                + UUID.randomUUID()
                + "\",");

    mockMvc
        .perform(
            post("/api/v1/campaigns")
                .header(AUTHORIZATION, bearer(advertiser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.advertiserId").value(advertiser.toString()))
        .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(forgedId.toString())));
  }

  @Test
  void reportsEveryInvalidFieldAsProblemJson() throws Exception {
    UUID advertiser = givenAdvertiser();
    String body =
        CampaignJson.VALID
            .replace("\"Thể thao tháng 11\"", "\"\"")
            .replace("\"dailyBudgetMicros\": 100000000", "\"dailyBudgetMicros\": 0")
            .replace("\"countries\": [\"VNM\"]", "\"countries\": [\"VN\"]");

    mockMvc
        .perform(
            post("/api/v1/campaigns")
                .header(AUTHORIZATION, bearer(advertiser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'dailyBudgetMicros')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'targeting.countries')]").exists())
        .andExpect(jsonPath("$.requestId").exists());
  }

  @Test
  void translationProblemsAreValidationErrorsToo() throws Exception {
    UUID advertiser = givenAdvertiser();
    String body =
        CampaignJson.VALID
            .replace("\"EVEN\"", "\"FAST\"")
            .replace("\"FIXED_CPM\"", "\"MAGIC\"")
            .replace("Asia/Ho_Chi_Minh", "Europe/Paris");

    mockMvc
        .perform(
            post("/api/v1/campaigns")
                .header(AUTHORIZATION, bearer(advertiser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'pacingMode')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'bidStrategy.type')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'targeting.schedule.timezone')]").exists());
  }

  @Test
  void aMissingPacingModeDefaultsToEven() throws Exception {
    UUID advertiser = givenAdvertiser();

    mockMvc
        .perform(
            post("/api/v1/campaigns")
                .header(AUTHORIZATION, bearer(advertiser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CampaignJson.VALID.replace("\"pacingMode\": \"EVEN\",", "")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.pacingMode").value("EVEN"));
  }

  @Test
  void malformedJsonIsAValidationError() throws Exception {
    UUID advertiser = givenAdvertiser();

    mockMvc
        .perform(
            post("/api/v1/campaigns")
                .header(AUTHORIZATION, bearer(advertiser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void requiresAToken() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/campaigns")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CampaignJson.VALID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }
}
