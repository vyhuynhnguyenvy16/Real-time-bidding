package dev.vy.rtb.campaign.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes a {@link Problem} straight to the response; used by the security filters. */
@Component
public class ProblemWriter {

  private final JsonMapper json;

  ProblemWriter(JsonMapper json) {
    this.json = json;
  }

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String title,
      String detail)
      throws IOException {
    Problem problem =
        Problem.of(
            status,
            code,
            title,
            detail,
            request.getRequestURI(),
            RequestIdFilter.of(request),
            null);
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(json.writeValueAsString(problem));
  }
}
