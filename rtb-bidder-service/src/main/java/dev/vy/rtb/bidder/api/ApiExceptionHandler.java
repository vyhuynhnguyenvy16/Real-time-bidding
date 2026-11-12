package dev.vy.rtb.bidder.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Bad input is a 400 with a code. The detail never echoes the body, so nothing leaks into logs. */
@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler(InvalidRequestException.class)
  ResponseEntity<Map<String, String>> invalid(InvalidRequestException e) {
    return body(e.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
    return body("the body is not a readable OpenRTB bid request");
  }

  private static ResponseEntity<Map<String, String>> body(String detail) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("code", "INVALID_REQUEST", "detail", detail));
  }
}
