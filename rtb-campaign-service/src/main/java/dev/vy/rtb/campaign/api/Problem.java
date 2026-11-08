package dev.vy.rtb.campaign.api;

import java.util.List;

/** RFC 9457 problem body of the control plane (docs/api-contract.md 3.6). */
public record Problem(
    String type,
    String title,
    int status,
    String code,
    String detail,
    String instance,
    String requestId,
    List<FieldError> errors) {

  public record FieldError(String field, String message) {}

  public static final String BASE_TYPE = "https://rtb.example/problems/";

  public static Problem of(
      int status,
      String code,
      String title,
      String detail,
      String instance,
      String requestId,
      List<FieldError> errors) {
    return new Problem(
        BASE_TYPE + code.toLowerCase().replace('_', '-'),
        title,
        status,
        code,
        detail,
        instance,
        requestId,
        errors == null || errors.isEmpty() ? null : errors);
  }
}
