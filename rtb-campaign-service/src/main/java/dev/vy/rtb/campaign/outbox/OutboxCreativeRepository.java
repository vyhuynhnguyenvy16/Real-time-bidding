package dev.vy.rtb.campaign.outbox;

import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.persistence.CampaignRepository;
import dev.vy.rtb.campaign.persistence.CreativeRepository;
import dev.vy.rtb.campaign.persistence.Stored;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A {@link CreativeRepository} that records the owning campaign's new state in the outbox after
 * every write, in the same transaction. The campaign row is locked first, so two writers touching
 * the same campaign take their outbox ids in the order they commit.
 */
public final class OutboxCreativeRepository implements CreativeRepository {

  private final CreativeRepository delegate;
  private final CampaignRepository campaigns;
  private final OutboxRecorder recorder;
  private final TransactionOperations transactions;

  /** {@code campaigns} must be the plain repository: it is only used for the row lock. */
  public OutboxCreativeRepository(
      CreativeRepository delegate,
      CampaignRepository campaigns,
      OutboxRecorder recorder,
      TransactionOperations transactions) {
    this.delegate = delegate;
    this.campaigns = campaigns;
    this.recorder = recorder;
    this.transactions = transactions;
  }

  @Override
  public Stored<Creative> insert(Creative creative) {
    return transactions.execute(
        tx -> {
          campaigns.findByIdForUpdate(creative.advertiserId(), creative.campaignId());
          Stored<Creative> stored = delegate.insert(creative);
          recorder.record(creative.advertiserId(), creative.campaignId());
          return stored;
        });
  }

  @Override
  public Optional<Stored<Creative>> findById(UUID advertiserId, UUID campaignId, UUID creativeId) {
    return delegate.findById(advertiserId, campaignId, creativeId);
  }

  @Override
  public List<Stored<Creative>> listByCampaign(UUID advertiserId, UUID campaignId) {
    return delegate.listByCampaign(advertiserId, campaignId);
  }

  @Override
  public Stored<Creative> update(Stored<Creative> current, Creative changed) {
    return transactions.execute(
        tx -> {
          campaigns.findByIdForUpdate(changed.advertiserId(), changed.campaignId());
          Stored<Creative> stored = delegate.update(current, changed);
          recorder.record(changed.advertiserId(), changed.campaignId());
          return stored;
        });
  }

  @Override
  public int countActive(UUID advertiserId, UUID campaignId) {
    return delegate.countActive(advertiserId, campaignId);
  }
}
