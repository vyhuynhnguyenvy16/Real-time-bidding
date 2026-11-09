package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.CampaignValidationException;
import dev.vy.rtb.campaign.domain.Creative;
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
 * JSON merge patch (RFC 7396) for a creative. A creative is flat, so merging is: a value replaces,
 * {@code null} removes (which the domain then rejects as missing). Only whitelisted members pass.
 */
@Component
class CreativePatcher {

  static final Set<String> EDITABLE = Set.of("name", "width", "height", "markup", "landingUrl");

  private final JsonMapper json;

  CreativePatcher(JsonMapper json) {
    this.json = json;
  }

  Creative apply(Creative current, Map<String, Object> patch) {
    List<Violation> problems = new ArrayList<>();
    for (String member : patch.keySet()) {
      if (!EDITABLE.contains(member)) {
        problems.add(new Violation(member, "cannot be changed or is not a creative field"));
      }
    }
    if (!problems.isEmpty()) {
      throw new CampaignValidationException(problems);
    }
    Map<String, Object> merged =
        json.convertValue(
            CreativeRequest.from(current), new TypeReference<LinkedHashMap<String, Object>>() {});
    for (Map.Entry<String, Object> entry : patch.entrySet()) {
      if (entry.getValue() == null) {
        merged.remove(entry.getKey());
      } else {
        merged.put(entry.getKey(), entry.getValue());
      }
    }
    CreativeRequest edited;
    try {
      edited = json.convertValue(merged, CreativeRequest.class);
    } catch (JacksonException | IllegalArgumentException e) {
      throw new CampaignValidationException(
          List.of(new Violation("body", "a value has the wrong type or format")));
    }
    return current.revise(edited.toNewCreative());
  }
}
