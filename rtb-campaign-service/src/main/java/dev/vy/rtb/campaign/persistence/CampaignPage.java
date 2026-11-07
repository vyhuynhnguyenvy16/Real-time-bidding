package dev.vy.rtb.campaign.persistence;

import dev.vy.rtb.campaign.domain.Campaign;
import java.util.List;

/** One page of campaigns; {@code next} is null on the last page. */
public record CampaignPage(List<Stored<Campaign>> items, CampaignCursor next) {}
