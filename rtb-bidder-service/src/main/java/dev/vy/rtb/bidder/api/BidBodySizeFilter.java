package dev.vy.rtb.bidder.api;

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
 * A bid request is at most 32 KB (docs/api-contract.md section 0). Checked against Content-Length
 * before the body is read; a body sent without a length is not counted yet.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class BidBodySizeFilter extends OncePerRequestFilter {

  static final long MAX_BYTES = 32 * 1024;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BYTES) {
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      return;
    }
    chain.doFilter(request, response);
  }
}
