package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.persistence.CampaignCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** The opaque {@code cursor} of the list endpoint: base64url of "createdAt|id". */
final class CursorCodec {

  private CursorCodec() {}

  static String encode(CampaignCursor cursor) {
    String raw = cursor.createdAt() + "|" + cursor.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * @throws BadRequestException when the text is not a cursor this server produced
   */
  static CampaignCursor decode(String text) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
      int bar = raw.indexOf('|');
      return new CampaignCursor(
          Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
    } catch (RuntimeException e) {
      throw new BadRequestException("cursor", "is not a valid cursor");
    }
  }
}
