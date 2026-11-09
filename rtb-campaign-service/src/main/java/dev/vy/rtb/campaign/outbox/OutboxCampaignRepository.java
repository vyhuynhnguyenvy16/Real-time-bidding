package dev.vy.rtb.campaign.outbox;

import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.CampaignStatus;
import dev.vy.rtb.campaign.persistence.CampaignCursor;
import dev.vy.rtb.campaign.persistence.CampaignPage;
import dev.vy.rtb.campaign.persistence.CampaignRepository;
import dev.vy.rtb.campaign.persistence.Stored;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A {@link CampaignRepository} that records every write in the outbox, in the same transaction.
 * Reads pass straight through.
 */
public final class OutboxCampaignRepository implements CampaignRepository {

  private final CampaignRepository delegate;
  private final OutboxRecorder recorder;
  private final TransactionOperations transactions;

  public OutboxCampaignRepository(
      CampaignRepository delegate, OutboxRecorder recorder, TransactionOperations transactions) {
    this.delegate = delegate;
    this.recorder = recorder;
    this.transactions = transactions;
  }

  @Override
  public Stored<Campaign> insert(Campaign campaign) {
    return transactions.execute(
        tx -> {
          Stored<Campaign> stored = delegate.insert(campaign);
          recorder.record(campaign.advertiserId(), campaign.id());
          return stored;
        });
  }

  @Override
  public Optional<Stored<Campaign>> findById(UUID advertiserId, UUID campaignId) {
    return delegate.findById(advertiserId, campaignId);
  }

  @Override
  public Optional<Stored<Campaign>> findByIdForUpdate(UUID advertiserId, UUID campaignId) {
    return delegate.findByIdForUpdate(advertiserId, campaignId);
  }

  @Override
  public Stored<Campaign> update(Stored<Campaign> current, Campaign changed) {
    return transactions.execute(
        tx -> {
          Stored<Campaign> stored = delegate.update(current, changed);
          recorder.record(changed.advertiserId(), changed.id());
          return stored;
        });
  }

  @Override
  public CampaignPage list(
      UUID advertiserId, CampaignStatus status, CampaignCursor after, int limit) {
    return delegate.list(advertiserId, status, after, limit);
  }
}
