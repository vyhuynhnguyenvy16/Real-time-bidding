package dev.vy.rtb.bidder.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vy.rtb.bidder.snapshot.SnapshotStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real application and real HTTP layer; Kafka is off and the snapshot is filled by hand. */
@SpringBootTest(
    properties = {"rtb.bidder.kafka.enabled=false", "rtb.bidder.exchange-key=test-exchange-key"})
@AutoConfigureMockMvc
class BidApiTest {

  private static final String URL = "/openrtb/2.6/bid";
  private static final String KEY = "test-exchange-key";
  private static final UUID CAMPAIGN = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String MASK = "1".repeat(168);

  @Autowired MockMvc mockMvc;
  @Autowired SnapshotStore store;

  @BeforeEach
  void emptySnapshot() {
    store.apply(List.of(new SnapshotStore.Change(CAMPAIGN.toString(), null)));
  }

  private void givenActiveCampaign() {
    String event =
        """
        {"schemaVersion":1,"revision":%d,"campaign":{"id":"%s","status":"ACTIVE",
         "maxBidCpmMicros":2000000,"bidStrategy":{"type":"FIXED_CPM","cpmMicros":850000},
         "startAt":"2020-01-01T00:00:00Z","endAt":"2099-01-01T00:00:00Z",
         "targeting":{"countries":["VNM"],"deviceTypes":[],"categories":[],
           "publisherBlocklist":["spam.example"],"scheduleMask":"%s"}},
         "creatives":[{"id":"33333333-3333-3333-3333-333333333333","width":300,"height":250,
           "markup":"<a>ad</a>","landingUrl":"https://example.com"}]}
        """
            .formatted(System.nanoTime(), CAMPAIGN, MASK);
    store.apply(List.of(new SnapshotStore.Change(CAMPAIGN.toString(), event)));
  }

  private static String request(String domain, String country, double floor) {
    return """
    {"id":"req-1","imp":[{"id":"1","banner":{"w":300,"h":250},"bidfloor":%s,
      "bidfloorcur":"USD"}],"site":{"domain":"%s"},
     "device":{"devicetype":2,"geo":{"country":"%s"}},"cur":["USD"],"at":1,"test":1}
    """
        .formatted(floor, domain, country);
  }

  private MockHttpServletRequestBuilder bid(String body) {
    return post(URL)
        .header("X-Exchange-Key", KEY)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  @Test
  void aMatchingRequestGetsOneBid() throws Exception {
    givenActiveCampaign();

    mockMvc
        .perform(bid(request("news.example", "VNM", 0.5)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("req-1"))
        .andExpect(jsonPath("$.cur").value("USD"))
        .andExpect(jsonPath("$.seatbid[0].bid.length()").value(1))
        .andExpect(jsonPath("$.seatbid[0].bid[0].impid").value("1"))
        .andExpect(jsonPath("$.seatbid[0].bid[0].price").value(0.85))
        .andExpect(jsonPath("$.seatbid[0].bid[0].adm").value("<a>ad</a>"))
        .andExpect(jsonPath("$.seatbid[0].bid[0].cid").value(CAMPAIGN.toString()))
        .andExpect(
            jsonPath("$.seatbid[0].bid[0].crid").value("33333333-3333-3333-3333-333333333333"))
        .andExpect(jsonPath("$.seatbid[0].bid[0].w").value(300))
        .andExpect(jsonPath("$.seatbid[0].bid[0].h").value(250));
  }

  @Test
  void aNoBidIsA204AndTheReasonIsOnlyShownForTestRequests() throws Exception {
    givenActiveCampaign();

    mockMvc
        .perform(bid(request("news.example", "SGP", 0.5)))
        .andExpect(status().isNoContent())
        .andExpect(header().string("X-NoBid-Reason", "GEO_MISMATCH"))
        .andExpect(content().string(""));

    mockMvc
        .perform(bid(request("news.example", "SGP", 0.5).replace("\"test\":1", "\"test\":0")))
        .andExpect(status().isNoContent())
        .andExpect(header().doesNotExist("X-NoBid-Reason"));
  }

  @Test
  void aBlockedPublisherAndAHighFloorAreNoBids() throws Exception {
    givenActiveCampaign();

    mockMvc
        .perform(bid(request("spam.example", "VNM", 0.5)))
        .andExpect(status().isNoContent())
        .andExpect(header().string("X-NoBid-Reason", "BLOCKED_PUBLISHER"));
    mockMvc
        .perform(bid(request("news.example", "VNM", 0.9)))
        .andExpect(status().isNoContent())
        .andExpect(header().string("X-NoBid-Reason", "BELOW_FLOOR"));
  }

  @Test
  void anEmptySnapshotIsANoCandidate() throws Exception {
    mockMvc
        .perform(bid(request("news.example", "VNM", 0.5)))
        .andExpect(status().isNoContent())
        .andExpect(header().string("X-NoBid-Reason", "NO_CANDIDATE"));
  }

  @Test
  void theExchangeKeyIsRequired() throws Exception {
    mockMvc
        .perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("a.example", "VNM", 0)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post(URL)
                .header("X-Exchange-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("a.example", "VNM", 0)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aBadRequestIsA400WithACode() throws Exception {
    for (String body :
        new String[] {
          "{not json",
          "{\"imp\":[{\"id\":\"1\",\"banner\":{\"w\":300,\"h\":250}}],\"site\":{\"domain\":\"a\"}}",
          "{\"id\":\"r\",\"imp\":[],\"site\":{\"domain\":\"a\"}}",
          "{\"id\":\"r\",\"imp\":[{\"id\":\"1\"}],\"site\":{\"domain\":\"a\"}}",
          "{\"id\":\"r\",\"imp\":[{\"id\":\"1\",\"banner\":{\"w\":300,\"h\":250}}]}",
          "{\"id\":\"r\",\"imp\":[{\"id\":\"1\",\"banner\":{\"w\":300,\"h\":250},\"bidfloorcur\":\"EUR\"}],\"site\":{\"domain\":\"a\"}}",
          "{\"id\":\"r\",\"imp\":[{\"id\":\"1\",\"banner\":{\"w\":300,\"h\":250}}],\"site\":{\"domain\":\"a\"},\"cur\":[\"EUR\"]}",
          "{\"id\":\"r\",\"imp\":[{\"id\":\"1\",\"banner\":{\"w\":300,\"h\":250},\"bidfloor\":-1}],\"site\":{\"domain\":\"a\"}}"
        }) {
      mockMvc
          .perform(bid(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
  }

  @Test
  void aBodyOver32KbIsA413() throws Exception {
    mockMvc.perform(bid("x".repeat(33 * 1024))).andExpect(status().isPayloadTooLarge());
  }

  @Test
  void liveAndReadyProbes() throws Exception {
    mockMvc.perform(get("/livez")).andExpect(status().isOk());
    mockMvc.perform(get("/readyz")).andExpect(status().isOk());
  }
}
