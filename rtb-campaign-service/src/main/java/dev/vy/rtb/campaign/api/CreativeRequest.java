package dev.vy.rtb.campaign.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.NewCreative;

/** Body of POST and the editable view of a creative for PATCH. Rules live in the domain. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreativeRequest(
    String name, Integer width, Integer height, String markup, String landingUrl) {

  static CreativeRequest from(Creative c) {
    return new CreativeRequest(c.name(), c.width(), c.height(), c.markup(), c.landingUrl());
  }

  /** A missing size becomes 0, which the domain rejects as "size" like any other bad size. */
  NewCreative toNewCreative() {
    return new NewCreative(
        name, width == null ? 0 : width, height == null ? 0 : height, markup, landingUrl);
  }
}
