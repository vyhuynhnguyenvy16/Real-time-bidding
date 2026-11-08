package dev.vy.rtb.campaign.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Control plane bodies are at most 256 KB (docs/api-contract.md section 0). The limit is checked
 * against Content-Length before the body is read; a body sent without a length is not counted yet.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class BodySizeFilter extends OncePerRequestFilter {

  static final long MAX_BYTES = 256 * 1024;

  private final ProblemWriter problems;

  BodySizeFilter(ProblemWriter problems) {
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BYTES) {
      problems.write(
          request,
          response,
          413,
          "PAYLOAD_TOO_LARGE",
          "Payload too large",
          "The request body must be at most " + MAX_BYTES + " bytes");
      return;
    }
    chain.doFilter(request, response);
  }
}
