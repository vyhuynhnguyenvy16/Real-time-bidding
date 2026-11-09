package dev.vy.rtb.campaign.persistence;

import java.util.UUID;

/**
 * How many active creatives a campaign has; a campaign needs at least one to be activated. The
 * implementation is {@link JdbcCreativeRepository}.
 */
public interface CreativeCounter {

  int countActive(UUID advertiserId, UUID campaignId);
}
