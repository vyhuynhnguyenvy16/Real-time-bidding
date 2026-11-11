package dev.vy.rtb.campaign.outbox;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code outbox} table. Callers own the transaction. */
public final class OutboxStore {

  /** One row; {@code payload} is null for a tombstone. */
  public record Row(long id, UUID campaignId, String payload) {}

  private final JdbcClient jdbc;

  public OutboxStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void append(UUID campaignId, String payloadOrNull) {
    jdbc.sql(
            "INSERT INTO outbox (campaign_id, payload) VALUES (:campaignId, CAST(:payload AS"
                + " json))")
        .param("campaignId", campaignId)
        .param("payload", payloadOrNull)
        .update();
  }

  /** Oldest rows first; ids only grow, so id order is write order for one campaign. */
  public List<Row> oldest(int limit) {
    return jdbc.sql(
            "SELECT id, campaign_id, payload::text AS payload FROM outbox ORDER BY id LIMIT :limit")
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Row(
                    rs.getLong("id"),
                    rs.getObject("campaign_id", UUID.class),
                    rs.getString("payload")))
        .list();
  }

  public void delete(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql("DELETE FROM outbox WHERE id IN (:ids)").param("ids", ids).update();
  }
}
