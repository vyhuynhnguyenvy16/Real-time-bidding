package dev.vy.rtb.campaign.persistence;

import static dev.vy.rtb.campaign.domain.CampaignTestData.validInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.CampaignStatus;
import dev.vy.rtb.campaign.domain.TargetingInput;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seam: CampaignRepository, against a real PostgreSQL 16 started by Testcontainers. */
class CampaignRepositoryTest {

  private static final UUID ADVERTISER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID ADVERTISER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");
  private static final Instant NOW_FOR_ACTIVATE = Instant.parse("2026-11-09T09:00:00Z");

  private MutableClock clock;
  private CampaignRepository repository;

  private static UUID id(int n) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = PostgresTestSupport.dataSource();
    PostgresTestSupport.emptyTables();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    for (UUID advertiser : List.of(ADVERTISER_A, ADVERTISER_B)) {
      jdbc.sql("INSERT INTO advertisers (id, name, status) VALUES (:id, 'Advertiser', 'ACTIVE')")
          .param("id", advertiser)
          .update();
    }
    clock = new MutableClock(T0);
    repository =
        new JdbcCampaignRepository(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), clock);
  }

  private static Campaign draft(UUID id, UUID advertiser) {
    return Campaign.draft(id, advertiser, validInput().build());
  }

  // --- insert and find ------------------------------------------------------------------------

  @Test
  void aCampaignComesBackExactlyAsItWasStoredAtVersionOne() {
    Campaign campaign = draft(id(1), ADVERTISER_A);

    Stored<Campaign> inserted = repository.insert(campaign);
    Optional<Stored<Campaign>> found = repository.findById(ADVERTISER_A, id(1));

    assertThat(inserted.version()).isEqualTo(1);
    assertThat(inserted.createdAt()).isEqualTo(T0);
    assertThat(inserted.updatedAt()).isEqualTo(T0);
    assertThat(found).contains(inserted);
    assertThat(found.orElseThrow().value()).isEqualTo(campaign);
  }

  @Test
  void targetingSurvivesTheRoundTripIncludingAwkwardText() {
    TargetingInput targeting =
        new TargetingInput(
            List.of("VNM", "USA"),
            List.of(1, 4, 5),
            List.of("iab17", "iab1-2"),
            List.of("we\"ird,name\\x.example", "{braces}.example", "plain.example"),
            Map.of("all", List.of(18, 19, 20), "sat", List.of(9)));
    Campaign campaign =
        Campaign.draft(id(1), ADVERTISER_A, validInput().targeting(targeting).build());

    repository.insert(campaign);
    Campaign loaded = repository.findById(ADVERTISER_A, id(1)).orElseThrow().value();

    assertThat(loaded.targeting()).isEqualTo(campaign.targeting());
    assertThat(loaded.targeting().publisherBlocklist())
        .containsExactly("we\"ird,name\\x.example", "{braces}.example", "plain.example");
  }

  @Test
  void noTargetingAtAllComesBackAsNoRestriction() {
    Campaign campaign = Campaign.draft(id(1), ADVERTISER_A, validInput().targeting(null).build());

    repository.insert(campaign);

    Campaign loaded = repository.findById(ADVERTISER_A, id(1)).orElseThrow().value();
    assertThat(loaded.targeting().countries()).isEmpty();
    assertThat(loaded.targeting().schedule().allowedHoursCount()).isEqualTo(168);
  }

  @Test
  void aMissingFrequencyCapAndATargetCpcStrategyAreKept() {
    Campaign campaign =
        Campaign.draft(
            id(1),
            ADVERTISER_A,
            validInput().freqCapCount(null).bidStrategy(BidStrategy.targetCpc(40_000)).build());

    repository.insert(campaign);

    Campaign loaded = repository.findById(ADVERTISER_A, id(1)).orElseThrow().value();
    assertThat(loaded.freqCapCount()).isNull();
    assertThat(loaded.bidStrategy()).isEqualTo(BidStrategy.targetCpc(40_000));
  }

  @Test
  void everyStatusCanBeStoredAndReadBack() {
    Campaign active = draft(id(2), ADVERTISER_A).activate(NOW_FOR_ACTIVATE, 1);
    Campaign paused = draft(id(3), ADVERTISER_A).activate(NOW_FOR_ACTIVATE, 1).pause();
    Campaign completed = draft(id(4), ADVERTISER_A).activate(NOW_FOR_ACTIVATE, 1).complete();
    Campaign archived = draft(id(5), ADVERTISER_A).archive();

    for (Campaign campaign :
        List.of(draft(id(1), ADVERTISER_A), active, paused, completed, archived)) {
      repository.insert(campaign);
      assertThat(repository.findById(ADVERTISER_A, campaign.id()).orElseThrow().value())
          .isEqualTo(campaign);
    }
  }

  @Test
  void anotherAdvertiserCannotSeeTheCampaign() {
    repository.insert(draft(id(1), ADVERTISER_A));

    assertThat(repository.findById(ADVERTISER_B, id(1))).isEmpty();
  }

  @Test
  void anUnknownIdIsEmpty() {
    assertThat(repository.findById(ADVERTISER_A, id(99))).isEmpty();
  }

  @Test
  void aCampaignOfAnUnknownAdvertiserIsRejected() {
    Campaign orphan = draft(id(1), UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));

    assertThatThrownBy(() -> repository.insert(orphan))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theSameIdCannotBeInsertedTwice() {
    repository.insert(draft(id(1), ADVERTISER_A));

    assertThatThrownBy(() -> repository.insert(draft(id(1), ADVERTISER_A)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  // --- update with optimistic locking ---------------------------------------------------------

  @Test
  void updatingBumpsTheVersionAndUpdatedAtButNotCreatedAt() {
    Campaign active = draft(id(1), ADVERTISER_A).activate(NOW_FOR_ACTIVATE, 1);
    Stored<Campaign> stored = repository.insert(active);
    clock.advance(Duration.ofMinutes(5));

    Stored<Campaign> updated = repository.update(stored, active.pause());

    assertThat(updated.version()).isEqualTo(2);
    assertThat(updated.createdAt()).isEqualTo(T0);
    assertThat(updated.updatedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    assertThat(updated.value().status()).isEqualTo(CampaignStatus.PAUSED);
    assertThat(repository.findById(ADVERTISER_A, id(1))).contains(updated);
  }

  @Test
  void anUpdateBasedOnAnOldVersionIsRejectedAndChangesNothing() {
    Campaign active = draft(id(1), ADVERTISER_A).activate(NOW_FOR_ACTIVATE, 1);
    Stored<Campaign> stored = repository.insert(active);
    Stored<Campaign> afterFirst = repository.update(stored, active.pause());

    assertThatThrownBy(() -> repository.update(stored, active.complete()))
        .isInstanceOf(VersionConflictException.class);

    assertThat(repository.findById(ADVERTISER_A, id(1))).contains(afterFirst);
  }

  @Test
  void anUpdateNeverReachesAnotherAdvertisersCampaign() {
    Stored<Campaign> ownedByA = repository.insert(draft(id(1), ADVERTISER_A));
    Campaign claimedByB = draft(id(1), ADVERTISER_B);
    Stored<Campaign> forged = new Stored<>(claimedByB, 1, T0, T0);

    assertThatThrownBy(() -> repository.update(forged, claimedByB.archive()))
        .isInstanceOf(VersionConflictException.class);

    assertThat(repository.findById(ADVERTISER_A, id(1))).contains(ownedByA);
  }

  @Test
  void anUpdateMustKeepTheSameIdentity() {
    Stored<Campaign> stored = repository.insert(draft(id(1), ADVERTISER_A));

    assertThatThrownBy(() -> repository.update(stored, draft(id(2), ADVERTISER_A)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.update(stored, draft(id(1), ADVERTISER_B)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- list with keyset pagination ------------------------------------------------------------

  private void insertFive() {
    for (int i = 1; i <= 5; i++) {
      clock.set(T0.plus(Duration.ofMinutes(i)));
      repository.insert(draft(id(i), ADVERTISER_A));
    }
    repository.insert(draft(id(50), ADVERTISER_B));
  }

  private static List<UUID> ids(CampaignPage page) {
    return page.items().stream().map(item -> item.value().id()).toList();
  }

  @Test
  void pagesWalkThroughAllCampaignsOldestFirstWithoutRepeats() {
    insertFive();

    CampaignPage first = repository.list(ADVERTISER_A, null, null, 2);
    CampaignPage second = repository.list(ADVERTISER_A, null, first.next(), 2);
    CampaignPage third = repository.list(ADVERTISER_A, null, second.next(), 2);

    assertThat(ids(first)).containsExactly(id(1), id(2));
    assertThat(ids(second)).containsExactly(id(3), id(4));
    assertThat(ids(third)).containsExactly(id(5));
    assertThat(third.next()).isNull();
  }

  @Test
  void aPageThatEndsExactlyAtTheLastItemHasNoNextCursor() {
    insertFive();

    CampaignPage all = repository.list(ADVERTISER_A, null, null, 5);

    assertThat(ids(all)).hasSize(5);
    assertThat(all.next()).isNull();
  }

  @Test
  void campaignsCreatedAtTheSameInstantAreOrderedById() {
    repository.insert(draft(id(9), ADVERTISER_A));
    repository.insert(draft(id(3), ADVERTISER_A));
    repository.insert(draft(id(6), ADVERTISER_A));

    assertThat(ids(repository.list(ADVERTISER_A, null, null, 10)))
        .containsExactly(id(3), id(6), id(9));
  }

  @Test
  void listCanFilterByStatus() {
    repository.insert(draft(id(1), ADVERTISER_A));
    repository.insert(draft(id(2), ADVERTISER_A).activate(NOW_FOR_ACTIVATE, 1));
    repository.insert(draft(id(3), ADVERTISER_A).activate(NOW_FOR_ACTIVATE, 1).pause());

    assertThat(ids(repository.list(ADVERTISER_A, CampaignStatus.ACTIVE, null, 10)))
        .containsExactly(id(2));
    assertThat(ids(repository.list(ADVERTISER_A, CampaignStatus.DRAFT, null, 10)))
        .containsExactly(id(1));
  }

  @Test
  void listNeverShowsAnotherAdvertisersCampaigns() {
    insertFive();

    assertThat(ids(repository.list(ADVERTISER_B, null, null, 10))).containsExactly(id(50));
  }

  @Test
  void theLimitMustBeBetweenOneAndTwoHundred() {
    assertThatThrownBy(() -> repository.list(ADVERTISER_A, null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.list(ADVERTISER_A, null, null, 201))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
