package dev.vy.rtb.campaign.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.IF_MATCH;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seam: through the real controllers, each change that lands writes exactly one event and each
 * request that changes nothing, or is refused, writes none.
 */
class OutboxApiTest extends ApiTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String CREATIVE =
      """
      {"name": "Banner A", "width": 300, "height": 250,
       "markup": "<img src=\\"https://cdn.example.com/a.png\\">",
       "landingUrl": "https://example.com/landing"}
      """;

  private List<Map<String, Object>> events() {
    return jdbc.sql("SELECT id, campaign_id, payload::text AS payload FROM outbox ORDER BY id")
        .query()
        .listOfRows();
  }

  private static JsonNode last(List<Map<String, Object>> rows) {
    return JSON.readTree((String) rows.get(rows.size() - 1).get("payload"));
  }

  private static String idOf(org.springframework.test.web.servlet.MvcResult result) {
    String location = result.getResponse().getHeader(LOCATION);
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private ResultActions post(UUID advertiser, String url, String body) throws Exception {
    var request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
            .header(AUTHORIZATION, bearer(advertiser));
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(request);
  }

  @Test
  void eachChangeThatLandsWritesOneEventAndNothingElseDoes() throws Exception {
    UUID advertiser = givenAdvertiser();

    String campaign = idOf(post(advertiser, "/api/v1/campaigns", CampaignJson.VALID).andReturn());
    assertThat(events()).hasSize(1);

    // A patch that changes nothing writes nothing.
    mockMvc
        .perform(
            patch("/api/v1/campaigns/" + campaign)
                .header(AUTHORIZATION, bearer(advertiser))
                .header(IF_MATCH, "\"1\"")
                .contentType("application/merge-patch+json")
                .content("{}"))
        .andExpect(status().isOk());
    assertThat(events()).hasSize(1);

    // A refused creative writes nothing; a valid one writes one event carrying it.
    post(advertiser, "/api/v1/campaigns/" + campaign + "/creatives", "{\"name\": \"\"}")
        .andExpect(status().isBadRequest());
    assertThat(events()).hasSize(1);
    String creative =
        idOf(
            post(advertiser, "/api/v1/campaigns/" + campaign + "/creatives", CREATIVE).andReturn());
    assertThat(events()).hasSize(2);
    assertThat(last(events()).get("creatives")).hasSize(1);

    // Activating, then pausing, the campaign: one event each; repeating a pause is a no-op.
    post(advertiser, "/api/v1/campaigns/" + campaign + "/activate", null)
        .andExpect(status().isOk());
    assertThat(last(events()).get("campaign").get("status").asText()).isEqualTo("ACTIVE");
    post(advertiser, "/api/v1/campaigns/" + campaign + "/pause", null).andExpect(status().isOk());
    post(advertiser, "/api/v1/campaigns/" + campaign + "/pause", null).andExpect(status().isOk());
    assertThat(events()).hasSize(4);
    assertThat(last(events()).get("campaign").get("status").asText()).isEqualTo("PAUSED");

    // A creative pause re-emits the campaign without that creative.
    post(advertiser, "/api/v1/campaigns/" + campaign + "/creatives/" + creative + "/pause", null)
        .andExpect(status().isOk());
    assertThat(events()).hasSize(5);
    assertThat(last(events()).get("creatives")).isEmpty();

    // Archiving the campaign leaves a tombstone.
    post(advertiser, "/api/v1/campaigns/" + campaign + "/archive", null).andExpect(status().isOk());
    List<Map<String, Object>> rows = events();
    assertThat(rows).hasSize(6);
    assertThat(rows.get(5).get("payload")).isNull();
    assertThat(rows)
        .allSatisfy(r -> assertThat(r.get("campaign_id").toString()).isEqualTo(campaign));
  }

  @Test
  void aRefusedTransitionWritesNoEvent() throws Exception {
    UUID advertiser = givenAdvertiser();
    String campaign = idOf(post(advertiser, "/api/v1/campaigns", CampaignJson.VALID).andReturn());

    post(advertiser, "/api/v1/campaigns/" + campaign + "/activate", null)
        .andExpect(status().isConflict()); // no creative yet

    assertThat(events()).hasSize(1);
  }
}
