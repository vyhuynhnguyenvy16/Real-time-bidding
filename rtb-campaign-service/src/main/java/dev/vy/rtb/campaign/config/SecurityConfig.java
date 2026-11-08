package dev.vy.rtb.campaign.config;

import dev.vy.rtb.campaign.api.ProblemWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server with HS256 JWTs. The tenant is the {@code advertiser_id} claim; a token without a
 * valid one is rejected here, so controllers can rely on it (docs/security.md).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  static final String ADVERTISER_CLAIM = "advertiser_id";
  private static final int MIN_SECRET_BYTES = 32;

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http, ProblemWriter problems) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(
                        (request, response, e) -> {
                          response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer"); // RFC 6750
                          problems.write(
                              request,
                              response,
                              HttpStatus.UNAUTHORIZED.value(),
                              "UNAUTHENTICATED",
                              "Authentication required",
                              "Missing, invalid or expired access token");
                        }))
        .exceptionHandling(
            ex ->
                ex.accessDeniedHandler(
                    (request, response, e) ->
                        problems.write(
                            request,
                            response,
                            HttpStatus.FORBIDDEN.value(),
                            "FORBIDDEN",
                            "Forbidden",
                            "The token does not allow this operation")))
        .build();
  }

  @Bean
  JwtDecoder jwtDecoder(@Value("${rtb.security.jwt-secret}") String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "rtb.security.jwt-secret must be at least " + MIN_SECRET_BYTES + " bytes");
    }
    SecretKey key = new SecretKeySpec(bytes, "HmacSHA256");
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), new AdvertiserClaimValidator()));
    return decoder;
  }

  /** A token is only useful with a tenant: {@code advertiser_id} must be a UUID. */
  static final class AdvertiserClaimValidator implements OAuth2TokenValidator<Jwt> {
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
      String claim = jwt.getClaimAsString(ADVERTISER_CLAIM);
      try {
        if (claim != null) {
          UUID.fromString(claim);
          return OAuth2TokenValidatorResult.success();
        }
      } catch (IllegalArgumentException ignored) {
        // falls through to the failure below
      }
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "advertiser_id claim must be a UUID", null));
    }
  }
}
