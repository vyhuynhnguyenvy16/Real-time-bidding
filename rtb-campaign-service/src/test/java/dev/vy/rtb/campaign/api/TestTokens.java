package dev.vy.rtb.campaign.api;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Signs access tokens the way the real issuer will, so tests go through the real decoder. */
final class TestTokens {

  static final String SECRET = "test-only-secret-test-only-secret-1234";

  private TestTokens() {}

  static String forAdvertiser(UUID advertiserId) {
    return sign(SECRET, advertiserId.toString(), Instant.now().plus(30, ChronoUnit.MINUTES));
  }

  static String sign(String secret, String advertiserClaim, Instant expiresAt) {
    var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    // An already expired token must still be issued before it expires, or the encoder refuses it.
    Instant issuedAt = Instant.now().minusSeconds(5);
    if (!expiresAt.isAfter(issuedAt)) {
      issuedAt = expiresAt.minusSeconds(60);
    }
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder().subject("test-user").issuedAt(issuedAt).expiresAt(expiresAt);
    if (advertiserClaim != null) {
      claims.claim("advertiser_id", advertiserClaim);
    }
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }
}
