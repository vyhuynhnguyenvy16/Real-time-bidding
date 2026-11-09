package dev.vy.rtb.campaign.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.NewCreative;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seam: CreativeRepository (and CreativeCounter) against a real PostgreSQL 16. */
class CreativeRepositoryTest {

  private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID CAMPAIGN = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_CAMPAIGN =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

  private MutableClock clock;
  private JdbcCreativeRepository repository;

  private static UUID id(int n) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = PostgresTestSupport.dataSource();
    PostgresTestSupport.emptyTables();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    for (UUID advertiser : List.of(A, B)) {
      jdbc.sql("INSERT INTO advertisers (id, name, status) VALUES (:id, 'Advertiser', 'ACTIVE')")
          .param("id", advertiser)
          .update();
    }
    for (UUID campaign : List.of(CAMPAIGN, OTHER_CAMPAIGN)) {
      jdbc.sql(
              """
              INSERT INTO campaigns (id, advertiser_id, name, status, daily_budget_micros,
                  total_budget_micros, max_bid_cpm_micros, bid_strategy, fixed_cpm_micros,
                  pacing_mode, start_at, end_at, version, created_at, updated_at)
              VALUES (:id, :a, 'C', 'DRAFT', 100000000, 1500000000, 2000000, 'FIXED_CPM', 850000,
                  'EVEN', now(), now() + interval '30 days', 1, now(), now())
              """)
          .param("id", campaign)
          .param("a", A)
          .update();
    }
    clock = new MutableClock(T0);
    repository =
        new JdbcCreativeRepository(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), clock);
  }

  private static NewCreative.Builder input() {
    return NewCreative.builder()
        .name("Banner")
        .width(300)
        .height(250)
        .markup("<a href=\"${CLICK_URL}\"><img src=\"https://cdn.example.com/a.png\"></a>")
        .landingUrl("https://example.com/landing");
  }

  private static Creative creative(int n) {
    return Creative.create(id(n), CAMPAIGN, A, input().name("Banner " + n).build());
  }

  @Test
  void aCreativeComesBackExactlyAsStoredAtVersionOne() {
    Creative creative = creative(1);

    Stored<Creative> inserted = repository.insert(creative);

    assertThat(inserted.version()).isEqualTo(1);
    assertThat(inserted.createdAt()).isEqualTo(T0);
    assertThat(repository.findById(A, CAMPAIGN, id(1))).contains(inserted);
    assertThat(inserted.value()).isEqualTo(creative);
  }

  @Test
  void anotherAdvertiserOrCampaignDoesNotSeeTheCreative() {
    repository.insert(creative(1));

    assertThat(repository.findById(B, CAMPAIGN, id(1))).isEmpty();
    assertThat(repository.findById(A, OTHER_CAMPAIGN, id(1))).isEmpty();
    assertThat(repository.listByCampaign(B, CAMPAIGN)).isEmpty();
  }

  @Test
  void aCreativeCannotBeAttachedToACampaignOfAnotherAdvertiser() {
    Creative foreign = Creative.create(id(1), CAMPAIGN, B, input().build());

    assertThatThrownBy(() -> repository.insert(foreign))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void updateBumpsTheVersionAndKeepsCreatedAt() {
    Stored<Creative> stored = repository.insert(creative(1));
    clock.advance(Duration.ofMinutes(5));

    Stored<Creative> updated = repository.update(stored, stored.value().pause());

    assertThat(updated.version()).isEqualTo(2);
    assertThat(updated.createdAt()).isEqualTo(T0);
    assertThat(updated.updatedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    assertThat(repository.findById(A, CAMPAIGN, id(1))).contains(updated);
  }

  @Test
  void aStaleVersionIsAConflictAndWritesNothing() {
    Stored<Creative> stored = repository.insert(creative(1));
    repository.update(stored, stored.value().pause());

    assertThatThrownBy(() -> repository.update(stored, stored.value().archive()))
        .isInstanceOf(VersionConflictException.class);
    assertThat(repository.findById(A, CAMPAIGN, id(1)).orElseThrow().value().status().name())
        .isEqualTo("PAUSED");
  }

  @Test
  void listsOneCampaignOldestFirst() {
    repository.insert(creative(2));
    clock.advance(Duration.ofSeconds(1));
    repository.insert(creative(1));
    repository.insert(Creative.create(id(9), OTHER_CAMPAIGN, A, input().build()));

    assertThat(repository.listByCampaign(A, CAMPAIGN))
        .extracting(s -> s.value().id())
        .containsExactly(id(2), id(1));
  }

  @Test
  void countsOnlyActiveCreativesOfThatCampaignAndAdvertiser() {
    Stored<Creative> first = repository.insert(creative(1));
    repository.insert(creative(2));
    repository.insert(creative(3));
    repository.update(first, first.value().pause());
    repository.insert(Creative.create(id(9), OTHER_CAMPAIGN, A, input().build()));

    assertThat(repository.countActive(A, CAMPAIGN)).isEqualTo(2);
    assertThat(repository.countActive(B, CAMPAIGN)).isZero();
  }

  @Test
  void aCampaignHoldsAtMostTwentyNonArchivedCreatives() {
    for (int i = 1; i <= 20; i++) {
      repository.insert(creative(i));
    }

    assertThatThrownBy(() -> repository.insert(creative(21)))
        .isInstanceOf(CreativeLimitException.class);

    // Archiving one frees a slot: archived creatives are history, not inventory.
    Stored<Creative> first = repository.findById(A, CAMPAIGN, id(1)).orElseThrow();
    repository.update(first, first.value().archive());
    assertThat(repository.insert(creative(21)).version()).isEqualTo(1);
  }
}
