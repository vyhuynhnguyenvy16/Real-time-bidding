package dev.vy.rtb.campaign.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.domain.NewCreative;
import dev.vy.rtb.campaign.domain.PacingMode;
import dev.vy.rtb.campaign.outbox.OutboxCampaignRepository;
import dev.vy.rtb.campaign.outbox.OutboxCreativeRepository;
import dev.vy.rtb.campaign.outbox.OutboxRecorder;
import dev.vy.rtb.campaign.outbox.OutboxStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seam: every write of a campaign or creative leaves exactly one outbox row, in the same
 * transaction. Real PostgreSQL, the real repositories, no Spring context.
 */
class OutboxRecordingTest {

  private static final UUID ADVERTISER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID CAMPAIGN = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private JdbcClient jdbc;
  private TransactionTemplate transactions;
  private CampaignRepository campaigns;
  private CreativeRepository creatives;

  @BeforeEach
  void setUp() {
    DataSource dataSource = PostgresTestSupport.dataSource();
    PostgresTestSupport.emptyTables();
    jdbc = JdbcClient.create(dataSource);
    jdbc.sql("INSERT INTO advertisers (id, name, status) VALUES (:id, 'Advertiser', 'ACTIVE')")
        .param("id", ADVERTISER)
        .update();
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    JdbcCampaignRepository plainCampaigns = new JdbcCampaignRepository(jdbc, transactions, clock);
    JdbcCreativeRepository plainCreatives = new JdbcCreativeRepository(jdbc, transactions, clock);
    OutboxRecorder recorder =
        new OutboxRecorder(plainCampaigns, plainCreatives, new OutboxStore(jdbc), JSON, clock);
    campaigns = new OutboxCampaignRepository(plainCampaigns, recorder, transactions);
    creatives =
        new OutboxCreativeRepository(plainCreatives, plainCampaigns, recorder, transactions);
  }

  private static Campaign draft() {
    return Campaign.draft(
        CAMPAIGN,
        ADVERTISER,
        NewCampaign.builder()
            .name("Outbox")
            .dailyBudgetMicros(100_000_000)
            .totalBudgetMicros(1_500_000_000)
            .maxBidCpmMicros(2_000_000)
            .bidStrategy(BidStrategy.fixedCpm(850_000))
            .pacingMode(PacingMode.EVEN)
            .startAt(Instant.parse("2026-11-01T00:00:00Z"))
            .endAt(Instant.parse("2026-12-10T00:00:00Z"))
            .build());
  }

  private static Creative creative(int n) {
    return Creative.create(
        UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n)),
        CAMPAIGN,
        ADVERTISER,
        NewCreative.builder()
            .name("Banner " + n)
            .width(300)
            .height(250)
            .markup("<img src=\"https://cdn.example.com/a.png\">")
            .landingUrl("https://example.com")
            .build());
  }

  private List<Map<String, Object>> rows() {
    return jdbc.sql("SELECT id, campaign_id, payload::text AS payload FROM outbox ORDER BY id")
        .query()
        .listOfRows();
  }

  private static JsonNode payload(Map<String, Object> row) {
    return JSON.readTree((String) row.get("payload"));
  }

  @Test
  void creatingACampaignWritesOneEventWithTheCampaignAndNoCreatives() {
    campaigns.insert(draft());

    List<Map<String, Object>> rows = rows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("campaign_id")).isEqualTo(CAMPAIGN);
    JsonNode event = payload(rows.get(0));
    assertThat(event.get("campaign").get("status").asText()).isEqualTo("DRAFT");
    assertThat(event.get("campaign").get("version").asLong()).isEqualTo(1);
    assertThat(event.get("creatives")).isEmpty();
  }

  @Test
  void aCreativeChangeReEmitsTheWholeCampaignWithItsActiveCreatives() {
    Stored<Campaign> campaign = campaigns.insert(draft());
    Stored<Creative> first = creatives.insert(creative(1));
    creatives.insert(creative(2));

    List<Map<String, Object>> afterInserts = rows();
    assertThat(afterInserts).hasSize(3);
    assertThat(payload(afterInserts.get(2)).get("creatives")).hasSize(2);

    creatives.update(first, first.value().pause());

    List<Map<String, Object>> rows = rows();
    assertThat(rows).hasSize(4);
    JsonNode last = payload(rows.get(3));
    assertThat(last.get("creatives")).hasSize(1);
    assertThat(last.get("creatives").get(0).get("name").asText()).isEqualTo("Banner 2");
    // The campaign version is the ETag of the campaign; a creative change must not move it.
    assertThat(last.get("campaign").get("version").asLong()).isEqualTo(campaign.version());
  }

  @Test
  void aCampaignUpdateEmitsTheNewVersionAndAnArchiveEmitsATombstone() {
    Stored<Campaign> created = campaigns.insert(draft());
    Stored<Campaign> archived = campaigns.update(created, created.value().archive());

    List<Map<String, Object>> rows = rows();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).get("campaign_id")).isEqualTo(CAMPAIGN);
    assertThat(rows.get(1).get("payload")).isNull(); // tombstone
    assertThat(archived.version()).isEqualTo(2);
  }

  @Test
  void aWriteThatFailsLeavesNoEvent() {
    Stored<Campaign> created = campaigns.insert(draft());
    Stored<Campaign> changed = campaigns.update(created, created.value().archive());

    // `created` is stale now, so this write conflicts.
    assertThatThrownBy(() -> campaigns.update(created, created.value().archive()))
        .isInstanceOf(VersionConflictException.class);

    assertThat(rows()).hasSize(2);
    assertThat(changed.version()).isEqualTo(2);
  }

  @Test
  void theEventAndTheWriteCommitOrRollBackTogether() {
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    tx -> {
                      campaigns.insert(draft());
                      throw new IllegalStateException("boom after the write");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(rows()).isEmpty();
    assertThat(campaigns.findById(ADVERTISER, CAMPAIGN)).isEmpty();
  }

  @Test
  void readsAreNotRecorded() {
    campaigns.insert(draft());
    creatives.insert(creative(1));

    campaigns.findById(ADVERTISER, CAMPAIGN);
    campaigns.list(ADVERTISER, null, null, 10);
    creatives.listByCampaign(ADVERTISER, CAMPAIGN);
    creatives.countActive(ADVERTISER, CAMPAIGN);

    assertThat(rows()).hasSize(2);
  }
}
