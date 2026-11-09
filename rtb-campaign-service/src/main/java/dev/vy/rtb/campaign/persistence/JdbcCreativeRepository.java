package dev.vy.rtb.campaign.persistence;

import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.CreativeStatus;
import dev.vy.rtb.campaign.domain.NewCreative;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** {@link CreativeRepository} on plain SQL (Spring JDBC). */
public final class JdbcCreativeRepository implements CreativeRepository {

  private static final String SELECT =
      """
      SELECT id, campaign_id, advertiser_id, name, width, height, markup, landing_url, status,
             version, created_at, updated_at
      FROM creatives
      """;

  private final JdbcClient jdbc;
  private final TransactionOperations transactions;
  private final Clock clock;

  public JdbcCreativeRepository(JdbcClient jdbc, TransactionOperations transactions, Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public Stored<Creative> insert(Creative c) {
    Instant now = now();
    transactions.executeWithoutResult(
        tx -> {
          // Lock the campaign row so two concurrent inserts cannot both pass the limit check.
          // A missing or foreign campaign locks nothing; the foreign key then rejects the insert.
          jdbc.sql("SELECT id FROM campaigns WHERE id = :c AND advertiser_id = :a FOR UPDATE")
              .param("c", c.campaignId())
              .param("a", c.advertiserId())
              .query()
              .listOfRows();
          int held =
              jdbc.sql(
                      """
                      SELECT count(*) FROM creatives
                      WHERE advertiser_id = :a AND campaign_id = :c AND status <> 'ARCHIVED'
                      """)
                  .param("a", c.advertiserId())
                  .param("c", c.campaignId())
                  .query(Integer.class)
                  .single();
          if (held >= MAX_PER_CAMPAIGN) {
            throw new CreativeLimitException(c.campaignId(), MAX_PER_CAMPAIGN);
          }
          jdbc.sql(
                  """
                  INSERT INTO creatives (id, campaign_id, advertiser_id, name, width, height,
                      markup, landing_url, status, version, created_at, updated_at)
                  VALUES (:id, :campaignId, :advertiserId, :name, :width, :height, :markup,
                      :landingUrl, :status, 1, :now, :now)
                  """)
              .param("id", c.id())
              .param("campaignId", c.campaignId())
              .param("advertiserId", c.advertiserId())
              .param("name", c.name())
              .param("width", c.width())
              .param("height", c.height())
              .param("markup", c.markup())
              .param("landingUrl", c.landingUrl())
              .param("status", c.status().name())
              .param("now", utc(now))
              .update();
        });
    return new Stored<>(c, 1, now, now);
  }

  @Override
  public Optional<Stored<Creative>> findById(UUID advertiserId, UUID campaignId, UUID creativeId) {
    return jdbc.sql(SELECT + " WHERE advertiser_id = :a AND campaign_id = :c AND id = :id")
        .param("a", advertiserId)
        .param("c", campaignId)
        .param("id", creativeId)
        .query(JdbcCreativeRepository::mapRow)
        .optional();
  }

  @Override
  public List<Stored<Creative>> listByCampaign(UUID advertiserId, UUID campaignId) {
    return jdbc.sql(
            SELECT + " WHERE advertiser_id = :a AND campaign_id = :c ORDER BY created_at, id")
        .param("a", advertiserId)
        .param("c", campaignId)
        .query(JdbcCreativeRepository::mapRow)
        .list();
  }

  @Override
  public Stored<Creative> update(Stored<Creative> current, Creative changed) {
    Creative before = current.value();
    if (!before.id().equals(changed.id())
        || !before.campaignId().equals(changed.campaignId())
        || !before.advertiserId().equals(changed.advertiserId())) {
      throw new IllegalArgumentException("An update must keep the id, campaign and advertiser");
    }
    Instant now = now();
    int rows =
        jdbc.sql(
                """
                UPDATE creatives
                SET name = :name, width = :width, height = :height, markup = :markup,
                    landing_url = :landingUrl, status = :status,
                    version = version + 1, updated_at = :now
                WHERE id = :id AND campaign_id = :campaignId AND advertiser_id = :advertiserId
                  AND version = :version
                """)
            .param("name", changed.name())
            .param("width", changed.width())
            .param("height", changed.height())
            .param("markup", changed.markup())
            .param("landingUrl", changed.landingUrl())
            .param("status", changed.status().name())
            .param("now", utc(now))
            .param("id", before.id())
            .param("campaignId", before.campaignId())
            .param("advertiserId", before.advertiserId())
            .param("version", current.version())
            .update();
    if (rows == 0) {
      throw new VersionConflictException(before.id(), current.version());
    }
    return new Stored<>(changed, current.version() + 1, current.createdAt(), now);
  }

  @Override
  public int countActive(UUID advertiserId, UUID campaignId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM creatives
            WHERE advertiser_id = :a AND campaign_id = :c AND status = 'ACTIVE'
            """)
        .param("a", advertiserId)
        .param("c", campaignId)
        .query(Integer.class)
        .single();
  }

  private static Stored<Creative> mapRow(ResultSet rs, int rowNum) throws SQLException {
    NewCreative stored =
        new NewCreative(
            rs.getString("name"),
            rs.getInt("width"),
            rs.getInt("height"),
            rs.getString("markup"),
            rs.getString("landing_url"));
    Creative creative =
        Creative.restore(
            rs.getObject("id", UUID.class),
            rs.getObject("campaign_id", UUID.class),
            rs.getObject("advertiser_id", UUID.class),
            stored,
            CreativeStatus.valueOf(rs.getString("status")));
    return new Stored<>(
        creative, rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
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
