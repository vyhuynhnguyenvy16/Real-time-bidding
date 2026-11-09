package dev.vy.rtb.campaign.persistence;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.CampaignStatus;
import dev.vy.rtb.campaign.domain.PacingMode;
import dev.vy.rtb.campaign.domain.Schedule;
import dev.vy.rtb.campaign.domain.Targeting;
import dev.vy.rtb.common.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link CampaignRepository} on plain SQL (Spring JDBC). A campaign and its targeting are one unit.
 */
public final class JdbcCampaignRepository implements CampaignRepository {

  private static final int MAX_PAGE_SIZE = 200;

  private static final String SELECT =
      """
      SELECT c.id, c.advertiser_id, c.name, c.status, c.daily_budget_micros, c.total_budget_micros,
             c.max_bid_cpm_micros, c.bid_strategy, c.fixed_cpm_micros, c.target_cpc_micros,
             c.pacing_mode, c.freq_cap_count, c.start_at, c.end_at,
             c.version, c.created_at, c.updated_at,
             t.countries, t.device_types, t.categories, t.publisher_blocklist,
             CAST(t.schedule_mask AS text) AS schedule_mask
      FROM campaigns c
      JOIN campaign_targeting t ON t.campaign_id = c.id
      """;

  private final JdbcClient jdbc;
  private final TransactionOperations transactions;
  private final Clock clock;

  public JdbcCampaignRepository(JdbcClient jdbc, TransactionOperations transactions, Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public Stored<Campaign> insert(Campaign campaign) {
    Instant now = now();
    transactions.executeWithoutResult(
        tx -> {
          jdbc.sql(
                  """
                  INSERT INTO campaigns (id, advertiser_id, name, status, daily_budget_micros,
                      total_budget_micros, max_bid_cpm_micros, bid_strategy, fixed_cpm_micros,
                      target_cpc_micros, pacing_mode, freq_cap_count, start_at, end_at,
                      version, created_at, updated_at)
                  VALUES (:id, :advertiserId, :name, :status, :daily, :total, :maxBid, :strategy,
                      :fixedCpm, :targetCpc, :pacing, :freqCap, :startAt, :endAt, 1, :now, :now)
                  """)
              .params(columnValues(campaign))
              .param("now", utc(now))
              .update();
          jdbc.sql(
                  """
                  INSERT INTO campaign_targeting (campaign_id, countries, device_types, categories,
                      publisher_blocklist, schedule_mask)
                  VALUES (:id, CAST(:countries AS text[]), CAST(:devices AS smallint[]),
                      CAST(:categories AS text[]), CAST(:blocklist AS text[]),
                      CAST(:mask AS bit(168)))
                  """)
              .params(targetingValues(campaign))
              .update();
        });
    return new Stored<>(campaign, 1, now, now);
  }

  @Override
  public Optional<Stored<Campaign>> findById(UUID advertiserId, UUID campaignId) {
    return jdbc.sql(SELECT + " WHERE c.advertiser_id = :advertiserId AND c.id = :id")
        .param("advertiserId", advertiserId)
        .param("id", campaignId)
        .query(JdbcCampaignRepository::mapRow)
        .optional();
  }

  @Override
  public Optional<Stored<Campaign>> findByIdForUpdate(UUID advertiserId, UUID campaignId) {
    return jdbc.sql(
            SELECT + " WHERE c.advertiser_id = :advertiserId AND c.id = :id FOR UPDATE OF c")
        .param("advertiserId", advertiserId)
        .param("id", campaignId)
        .query(JdbcCampaignRepository::mapRow)
        .optional();
  }

  @Override
  public Stored<Campaign> update(Stored<Campaign> current, Campaign changed) {
    Campaign before = current.value();
    if (!before.id().equals(changed.id())
        || !before.advertiserId().equals(changed.advertiserId())) {
      throw new IllegalArgumentException("An update must keep the id and the advertiser");
    }
    Instant now = now();
    return transactions.execute(
        tx -> {
          int rows =
              jdbc.sql(
                      """
                      UPDATE campaigns
                      SET name = :name, status = :status, daily_budget_micros = :daily,
                          total_budget_micros = :total, max_bid_cpm_micros = :maxBid,
                          bid_strategy = :strategy, fixed_cpm_micros = :fixedCpm,
                          target_cpc_micros = :targetCpc, pacing_mode = :pacing,
                          freq_cap_count = :freqCap, start_at = :startAt, end_at = :endAt,
                          version = version + 1, updated_at = :now
                      WHERE id = :id AND advertiser_id = :advertiserId AND version = :version
                      """)
                  .params(columnValues(changed))
                  .param("now", utc(now))
                  .param("version", current.version())
                  .update();
          if (rows == 0) {
            throw new VersionConflictException(before.id(), current.version());
          }
          // Tenant safety: the guarded campaigns UPDATE above already proved this campaign belongs
          // to the advertiser and that the version matched, inside the same transaction.
          int targetingRows =
              jdbc.sql(
                      """
                      UPDATE campaign_targeting
                      SET countries = CAST(:countries AS text[]),
                          device_types = CAST(:devices AS smallint[]),
                          categories = CAST(:categories AS text[]),
                          publisher_blocklist = CAST(:blocklist AS text[]),
                          schedule_mask = CAST(:mask AS bit(168))
                      WHERE campaign_id = :id
                      """)
                  .params(targetingValues(changed))
                  .update();
          if (targetingRows != 1) {
            throw new IllegalStateException(
                "Campaign " + before.id() + " has no targeting row; the data is inconsistent");
          }
          return new Stored<>(changed, current.version() + 1, current.createdAt(), now);
        });
  }

  @Override
  public CampaignPage list(
      UUID advertiserId, CampaignStatus status, CampaignCursor after, int limit) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
    }
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE c.advertiser_id = :advertiserId");
    Map<String, Object> params = new HashMap<>();
    params.put("advertiserId", advertiserId);
    if (status != null) {
      sql.append(" AND c.status = :status");
      params.put("status", status.name());
    }
    if (after != null) {
      sql.append(" AND (c.created_at, c.id) > (:afterAt, :afterId)");
      params.put("afterAt", utc(after.createdAt()));
      params.put("afterId", after.id());
    }
    // One extra row tells whether another page exists.
    sql.append(" ORDER BY c.created_at, c.id LIMIT :limit");
    params.put("limit", limit + 1);

    JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).params(params);
    List<Stored<Campaign>> rows = statement.query(JdbcCampaignRepository::mapRow).list();

    if (rows.size() <= limit) {
      return new CampaignPage(rows, null);
    }
    List<Stored<Campaign>> items = List.copyOf(rows.subList(0, limit));
    Stored<Campaign> last = items.get(limit - 1);
    return new CampaignPage(items, new CampaignCursor(last.createdAt(), last.value().id()));
  }

  // --- mapping --------------------------------------------------------------------------------

  private static Map<String, Object> columnValues(Campaign c) {
    boolean fixed = c.bidStrategy().type() == BidStrategy.Type.FIXED_CPM;
    Map<String, Object> values = new HashMap<>();
    values.put("id", c.id());
    values.put("advertiserId", c.advertiserId());
    values.put("name", c.name());
    values.put("status", c.status().name());
    values.put("daily", c.dailyBudget().micros());
    values.put("total", c.totalBudget().micros());
    values.put("maxBid", c.maxBidCpm().micros());
    values.put("strategy", c.bidStrategy().type().name());
    values.put("fixedCpm", fixed ? c.bidStrategy().valueMicros() : null);
    values.put("targetCpc", fixed ? null : c.bidStrategy().valueMicros());
    values.put("pacing", c.pacingMode().name());
    values.put("freqCap", c.freqCapCount());
    values.put("startAt", utc(c.startAt()));
    values.put("endAt", utc(c.endAt()));
    return values;
  }

  private static Map<String, Object> targetingValues(Campaign c) {
    Targeting t = c.targeting();
    return Map.of(
        "id", c.id(),
        "countries", PgArrays.textArray(t.countries()),
        "devices", PgArrays.intArray(t.deviceTypes()),
        "categories", PgArrays.textArray(t.categories()),
        "blocklist", PgArrays.textArray(t.publisherBlocklist()),
        "mask", t.schedule().toBitString());
  }

  private static Stored<Campaign> mapRow(ResultSet rs, int rowNum) throws SQLException {
    String strategy = rs.getString("bid_strategy");
    BidStrategy bidStrategy =
        BidStrategy.Type.valueOf(strategy) == BidStrategy.Type.FIXED_CPM
            ? BidStrategy.fixedCpm(rs.getLong("fixed_cpm_micros"))
            : BidStrategy.targetCpc(rs.getLong("target_cpc_micros"));
    int freqCap = rs.getInt("freq_cap_count");
    Integer freqCapCount = rs.wasNull() ? null : freqCap;
    Targeting targeting =
        new Targeting(
            PgArrays.toStrings(rs.getArray("countries")),
            PgArrays.toInts(rs.getArray("device_types")),
            PgArrays.toStrings(rs.getArray("categories")),
            PgArrays.toStrings(rs.getArray("publisher_blocklist")),
            Schedule.fromBitString(rs.getString("schedule_mask")));
    Campaign campaign =
        Campaign.restore(
            rs.getObject("id", UUID.class),
            rs.getObject("advertiser_id", UUID.class),
            rs.getString("name"),
            CampaignStatus.valueOf(rs.getString("status")),
            Money.ofMicros(rs.getLong("daily_budget_micros")),
            Money.ofMicros(rs.getLong("total_budget_micros")),
            Money.ofMicros(rs.getLong("max_bid_cpm_micros")),
            bidStrategy,
            PacingMode.valueOf(rs.getString("pacing_mode")),
            freqCapCount,
            instant(rs, "start_at"),
            instant(rs, "end_at"),
            targeting);
    return new Stored<>(
        campaign, rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** PostgreSQL stores microseconds, so cut the clock to that to read back what was written. */
  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }
}
