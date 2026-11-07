package dev.vy.rtb.campaign.api;

/** Request bodies for the API tests; the valid one is the example of docs/api-contract.md 3.2. */
final class CampaignJson {

  static final String VALID =
      """
      {
        "name": "Thể thao tháng 11",
        "dailyBudgetMicros": 100000000,
        "totalBudgetMicros": 1500000000,
        "maxBidCpmMicros": 2000000,
        "bidStrategy": { "type": "FIXED_CPM", "cpmMicros": 850000 },
        "pacingMode": "EVEN",
        "freqCapCount": 3,
        "startAt": "2026-11-10T00:00:00Z",
        "endAt": "2026-12-10T00:00:00Z",
        "targeting": {
          "countries": ["VNM"],
          "deviceTypes": [1, 4, 5],
          "categories": ["IAB17"],
          "publisherBlocklist": ["Spam.Example"],
          "schedule": { "timezone": "Asia/Ho_Chi_Minh", "hours": { "all": [18, 19, 20, 21, 22] } }
        }
      }
      """;

  private CampaignJson() {}
}
