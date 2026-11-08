package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.CampaignValidationException;
import dev.vy.rtb.campaign.domain.Violation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies a JSON merge patch (RFC 7396) to a campaign: {@code null} removes a member, an object is
 * merged member by member, anything else replaces. Only whitelisted members are accepted, so a
 * client cannot touch id, status, version or the advertiser (mass assignment).
 */
@Component
class CampaignPatcher {

  static final Set<String> EDITABLE =
      Set.of(
          "name",
          "dailyBudgetMicros",
          "totalBudgetMicros",
          "maxBidCpmMicros",
          "bidStrategy",
          "pacingMode",
          "freqCapCount",
          "startAt",
          "endAt",
          "targeting");

  private final JsonMapper json;

  CampaignPatcher(JsonMapper json) {
    this.json = json;
  }

  /** Returns the revised campaign; the domain validates the result like a new campaign. */
  Campaign apply(Campaign current, Map<String, Object> patch) {
    rejectMembersThatAreNotEditable(patch);
    Map<String, Object> merged =
        json.convertValue(
            CampaignRequest.from(current), new TypeReference<LinkedHashMap<String, Object>>() {});
    merge(merged, patch);
    CampaignRequest edited;
    try {
      edited = json.convertValue(merged, CampaignRequest.class);
    } catch (JacksonException | IllegalArgumentException e) {
      throw new CampaignValidationException(
          List.of(new Violation("body", "a value has the wrong type or format")));
    }
    return current.revise(CampaignRequestMapper.toNewCampaign(edited));
  }

  private static void rejectMembersThatAreNotEditable(Map<String, Object> patch) {
    List<Violation> problems = new ArrayList<>();
    for (String member : patch.keySet()) {
      if (!EDITABLE.contains(member)) {
        problems.add(new Violation(member, "cannot be changed or is not a campaign field"));
      }
    }
    if (!problems.isEmpty()) {
      throw new CampaignValidationException(problems);
    }
  }

  @SuppressWarnings("unchecked")
  private static void merge(Map<String, Object> target, Map<String, Object> patch) {
    for (Map.Entry<String, Object> entry : patch.entrySet()) {
      Object value = entry.getValue();
      Object existing = target.get(entry.getKey());
      if (value == null) {
        target.remove(entry.getKey());
      } else if (value instanceof Map<?, ?> patchMap && existing instanceof Map<?, ?> targetMap) {
        merge((Map<String, Object>) targetMap, (Map<String, Object>) patchMap);
      } else {
        target.put(entry.getKey(), value);
      }
    }
  }
}
