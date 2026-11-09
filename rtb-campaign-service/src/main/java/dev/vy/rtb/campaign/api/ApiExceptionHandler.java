package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.CampaignValidationException;
import dev.vy.rtb.campaign.domain.CreativeStateException;
import dev.vy.rtb.campaign.domain.InvalidStateException;
import dev.vy.rtb.campaign.persistence.CreativeLimitException;
import dev.vy.rtb.campaign.persistence.VersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
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

  @ExceptionHandler(CreativeNotFoundException.class)
  ResponseEntity<Problem> creativeNotFound(HttpServletRequest request) {
    return problem(request, HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", "Creative not found");
  }

  @ExceptionHandler(CreativeStateException.class)
  ResponseEntity<Problem> creativeState(HttpServletRequest request, CreativeStateException e) {
    return problem(request, HttpStatus.CONFLICT, "INVALID_STATE", "Invalid state", e.getMessage());
  }

  @ExceptionHandler(CreativeLimitException.class)
  ResponseEntity<Problem> creativeLimit(HttpServletRequest request, CreativeLimitException e) {
    return problem(
        request,
        HttpStatus.CONFLICT,
        "CREATIVE_LIMIT",
        "Creative limit reached",
        "A campaign holds at most " + e.limit() + " creatives that are not archived");
  }

  @ExceptionHandler(LastActiveCreativeException.class)
  ResponseEntity<Problem> lastActiveCreative(
      HttpServletRequest request, LastActiveCreativeException e) {
    return problem(
        request,
        HttpStatus.CONFLICT,
        "LAST_ACTIVE_CREATIVE",
        "Last active creative",
        e.getMessage());
  }

  @ExceptionHandler(CampaignValidationException.class)
  ResponseEntity<Problem> invalid(HttpServletRequest request, CampaignValidationException e) {
    List<Problem.FieldError> errors =
        e.violations().stream().map(v -> new Problem.FieldError(v.field(), v.message())).toList();
    return problem(
        request,
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Validation failed",
        errors.get(0).field() + ": " + errors.get(0).message(),
        errors);
  }

  @ExceptionHandler(BadRequestException.class)
  ResponseEntity<Problem> badRequest(HttpServletRequest request, BadRequestException e) {
    return problem(
        request,
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Validation failed",
        e.field() + ": " + e.getMessage(),
        List.of(new Problem.FieldError(e.field(), e.getMessage())));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Problem> unreadable(HttpServletRequest request) {
    return problem(
        request,
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Validation failed",
        "The request body is missing or is not valid JSON of the expected shape");
  }

  @ExceptionHandler(InvalidStateException.class)
  ResponseEntity<Problem> invalidState(HttpServletRequest request, InvalidStateException e) {
    return problem(request, HttpStatus.CONFLICT, "INVALID_STATE", "Invalid state", e.getMessage());
  }

  @ExceptionHandler(VersionConflictException.class)
  ResponseEntity<Problem> versionConflict(HttpServletRequest request) {
    return problem(
        request,
        HttpStatus.PRECONDITION_FAILED,
        "VERSION_CONFLICT",
        "Version conflict",
        "It changed since you read it; read it again and retry");
  }

  @ExceptionHandler(PreconditionRequiredException.class)
  ResponseEntity<Problem> preconditionRequired(
      HttpServletRequest request, PreconditionRequiredException e) {
    return problem(
        request,
        HttpStatus.PRECONDITION_REQUIRED,
        "PRECONDITION_REQUIRED",
        "Precondition required",
        e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Problem> unexpected(HttpServletRequest request, Exception e) {
    if (e instanceof ErrorResponse spring && spring.getStatusCode().is4xxClientError()) {
      return fromSpring(request, spring);
    }
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

  /** Errors Spring itself detects (wrong method, wrong media type, unknown path). */
  private static ResponseEntity<Problem> fromSpring(HttpServletRequest request, ErrorResponse e) {
    HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
    String code =
        switch (status) {
          case NOT_FOUND -> "NOT_FOUND";
          case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
          case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
          case NOT_ACCEPTABLE -> "NOT_ACCEPTABLE";
          default -> "VALIDATION_ERROR";
        };
    return problem(request, status, code, status.getReasonPhrase(), "The request cannot be served");
  }

  static ResponseEntity<Problem> problem(
      HttpServletRequest request, HttpStatus status, String code, String title, String detail) {
    return problem(request, status, code, title, detail, null);
  }

  static ResponseEntity<Problem> problem(
      HttpServletRequest request,
      HttpStatus status,
      String code,
      String title,
      String detail,
      List<Problem.FieldError> errors) {
    Problem body =
        Problem.of(
            status.value(),
            code,
            title,
            detail,
            request.getRequestURI(),
            RequestIdFilter.of(request),
            errors);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
