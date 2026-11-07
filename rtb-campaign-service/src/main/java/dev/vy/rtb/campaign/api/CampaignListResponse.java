package dev.vy.rtb.campaign.api;

import java.util.List;

/** One page of campaigns. {@code nextCursor} is absent on the last page. */
public record CampaignListResponse(List<CampaignResponse> items, String nextCursor) {}
