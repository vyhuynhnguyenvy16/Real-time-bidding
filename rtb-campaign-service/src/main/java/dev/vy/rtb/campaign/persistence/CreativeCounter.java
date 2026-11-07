package dev.vy.rtb.campaign.persistence;

import java.util.UUID;

/**
 * How many valid, active creatives a campaign has. Creatives arrive in a later PR; until then the
 * configured implementation reports none, so a campaign cannot be activated yet.
 */
public interface CreativeCounter {

  int countActive(UUID advertiserId, UUID campaignId);
}
