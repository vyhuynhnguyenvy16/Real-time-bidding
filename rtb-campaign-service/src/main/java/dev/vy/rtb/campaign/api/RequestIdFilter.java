package dev.vy.rtb.campaign.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id (the caller's {@code X-Request-Id}, or a new one) before anything else.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";
  public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
  private static final int MAX_LENGTH = 64;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = request.getHeader(HEADER);
    if (id == null || id.isBlank() || id.length() > MAX_LENGTH) {
      id = UUID.randomUUID().toString();
    }
    request.setAttribute(ATTRIBUTE, id);
    response.setHeader(HEADER, id);
    MDC.put("requestId", id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
    }
  }

  public static String of(HttpServletRequest request) {
    Object id = request.getAttribute(ATTRIBUTE);
    return id == null ? null : id.toString();
  }
}
