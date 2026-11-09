package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.persistence.CampaignRepository;
import dev.vy.rtb.campaign.persistence.Stored;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Real application, real PostgreSQL, real security chain; only the HTTP server is replaced. */
@SpringBootTest(
    properties = {
      "rtb.security.jwt-secret=" + TestTokens.SECRET,
      // application.yaml has no default for the password; the test container supplies the real one.
      "DB_PASSWORD=unused-in-tests"
    })
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
abstract class ApiTestBase {

  @Autowired protected MockMvc mockMvc;
  @Autowired protected CampaignRepository repository;
  @Autowired protected JdbcClient jdbc;

  @BeforeEach
  void emptyTables() {
    jdbc.sql("TRUNCATE outbox, creatives, campaign_targeting, campaigns, advertisers CASCADE")
        .update();
  }

  protected UUID givenAdvertiser() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO advertisers (id, name, status) VALUES (:id, 'Advertiser', 'ACTIVE')")
        .param("id", id)
        .update();
    return id;
  }

  protected Stored<Campaign> givenCampaign(UUID advertiserId, NewCampaign input) {
    return repository.insert(Campaign.draft(UUID.randomUUID(), advertiserId, input));
  }

  protected static String bearer(UUID advertiserId) {
    return "Bearer " + TestTokens.forAdvertiser(advertiserId);
  }
}
