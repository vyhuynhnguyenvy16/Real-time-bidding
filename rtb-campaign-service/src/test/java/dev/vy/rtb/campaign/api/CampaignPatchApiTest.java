package dev.vy.rtb.campaign.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.IF_MATCH;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.domain.PacingMode;
import dev.vy.rtb.campaign.domain.TargetingInput;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Seam: PATCH /api/v1/campaigns/{id} as a JSON merge patch guarded by If-Match. */
class CampaignPatchApiTest extends ApiTestBase {

  private static final String MERGE_PATCH = "application/merge-patch+json";

  private static NewCampaign input() {
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
                Map.of("sat", List.of(9))))
        .build();
  }

  private ResultActions patch(UUID advertiser, UUID id, String ifMatch, String body)
      throws Exception {
    var request =
        patch("/api/v1/campaigns/" + id)
            .header(AUTHORIZATION, bearer(advertiser))
            .contentType(MERGE_PATCH)
            .content(body);
    if (ifMatch != null) {
      request.header(IF_MATCH, ifMatch);
    }
    return mockMvc.perform(request);
  }

  @Test
  void changesOnlyTheGivenFieldsAndBumpsTheVersion() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    patch(advertiser, id, "\"1\"", "{\"name\": \"Tên mới\"}")
        .andExpect(status().isOk())
        .andExpect(header().string(ETAG, "\"2\""))
        .andExpect(jsonPath("$.name").value("Tên mới"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.dailyBudgetMicros").value(100_000_000))
        .andExpect(jsonPath("$.freqCapCount").value(3))
        .andExpect(jsonPath("$.targeting.countries[0]").value("VNM"));
  }

  @Test
  void mergesNestedObjectsMemberByMember() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    patch(advertiser, id, "\"1\"", "{\"targeting\": {\"countries\": [\"USA\"]}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targeting.countries[0]").value("USA"))
        .andExpect(jsonPath("$.targeting.deviceTypes.length()").value(3))
        .andExpect(jsonPath("$.targeting.publisherBlocklist[0]").value("spam.example"));
  }

  @Test
  void nullRemovesTheFrequencyCap() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    patch(advertiser, id, "\"1\"", "{\"freqCapCount\": null}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freqCapCount").doesNotExist());
  }

  @Test
  void acceptsAnIfMatchWithoutQuotes() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    patch(advertiser, id, "1", "{\"name\": \"x\"}").andExpect(status().isOk());
  }

  @Test
  void requiresIfMatch() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    patch(advertiser, id, null, "{\"name\": \"x\"}")
        .andExpect(status().isPreconditionRequired())
        .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
  }

  @Test
  void aStaleIfMatchIsAVersionConflictAndChangesNothing() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();
    patch(advertiser, id, "\"1\"", "{\"name\": \"first\"}").andExpect(status().isOk());

    patch(advertiser, id, "\"1\"", "{\"name\": \"second\"}")
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

    assertThat(repository.findById(advertiser, id).orElseThrow().value().name()).isEqualTo("first");
  }

  @Test
  void anIfMatchThatIsNotAVersionIsAValidationError() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    patch(advertiser, id, "\"abc\"", "{\"name\": \"x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("If-Match"));
  }

  @Test
  void membersTheServerOwnsOrThatDoNotExistAreRejected() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    patch(advertiser, id, "\"1\"", "{\"status\": \"ACTIVE\", \"version\": 7, \"colour\": \"red\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[?(@.field == 'status')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'version')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'colour')]").exists());
  }

  @Test
  void aPatchThatBreaksARuleIsRejectedAndNothingIsStored() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    patch(advertiser, id, "\"1\"", "{\"dailyBudgetMicros\": 0}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'dailyBudgetMicros')]").exists());

    assertThat(repository.findById(advertiser, id).orElseThrow().version()).isEqualTo(1);
  }

  @Test
  void aValueOfTheWrongTypeIsAValidationError() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    patch(advertiser, id, "\"1\"", "{\"dailyBudgetMicros\": \"lots\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void anArchivedCampaignCannotBeEdited() throws Exception {
    UUID advertiser = givenAdvertiser();
    var stored = givenCampaign(advertiser, input());
    repository.update(stored, stored.value().archive());

    patch(advertiser, stored.value().id(), "\"2\"", "{\"name\": \"x\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void anotherAdvertisersCampaignLooksLikeItDoesNotExist() throws Exception {
    UUID mine = givenAdvertiser();
    UUID other = givenAdvertiser();
    UUID id = givenCampaign(other, input()).value().id();

    patch(mine, id, "\"1\"", "{\"name\": \"x\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void onlyTheMergePatchMediaTypeIsAccepted() throws Exception {
    UUID advertiser = givenAdvertiser();
    UUID id = givenCampaign(advertiser, input()).value().id();

    mockMvc
        .perform(
            patch("/api/v1/campaigns/" + id)
                .header(AUTHORIZATION, bearer(advertiser))
                .header(IF_MATCH, "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"x\"}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }
}
