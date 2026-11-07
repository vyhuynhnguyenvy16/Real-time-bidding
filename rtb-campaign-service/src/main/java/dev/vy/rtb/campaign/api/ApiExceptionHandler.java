package dev.vy.rtb.campaign.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns exceptions into problem+json. Unexpected errors never leak a stack trace. */
@RestControllerAdvice
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler({CampaignNotFoundException.class, MethodArgumentTypeMismatchException.class})
  ResponseEntity<Problem> notFound(HttpServletRequest request) {
    return problem(request, HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", "Campaign not found");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Problem> unexpected(HttpServletRequest request, Exception e) {
    log.error(
        "Unexpected error on {} (requestId={})",
        request.getRequestURI(),
        RequestIdFilter.of(request),
        e);
    return problem(
        request,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL",
        "Internal error",
        "Unexpected error");
  }

  static ResponseEntity<Problem> problem(
      HttpServletRequest request, HttpStatus status, String code, String title, String detail) {
    Problem body =
        Problem.of(
            status.value(),
            code,
            title,
            detail,
            request.getRequestURI(),
            RequestIdFilter.of(request),
            null);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
